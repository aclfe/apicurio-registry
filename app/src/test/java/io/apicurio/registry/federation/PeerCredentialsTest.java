package io.apicurio.registry.federation;

import io.apicurio.registry.storage.dto.PeerDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Reading the credential a peer is searched with, from the credentials directory.
 */
class PeerCredentialsTest {

    private static final String TOKEN = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJwZWVyIn0.c2lnbmF0dXJl";

    @TempDir
    Path root;

    private static PeerDto peer(String credentialSecretRef) {
        return PeerDto.builder().peerId("eu-west").url("https://eu.example.com").enabled(true)
                .credentialSecretRef(credentialSecretRef).build();
    }

    private Path credentialsDir() throws IOException {
        return Files.createDirectories(root.resolve("credentials"));
    }

    private static Optional<String> token(Path dir, String reference) {
        return PeerCredentials.bearerToken(Optional.of(dir), peer(reference));
    }

    /** What the call fails with; the credential must never be part of it. */
    private static String unavailable(Path dir, String reference) {
        PeerSearchException.CredentialUnavailable failure = assertThrows(
                PeerSearchException.CredentialUnavailable.class, () -> token(dir, reference));
        assertFalse(failure.getMessage().contains(TOKEN), failure.getMessage());
        return failure.getMessage();
    }

    @Test
    void withoutACredentialsDirectoryEveryPeerIsSearchedAnonymously() throws IOException {
        Files.writeString(credentialsDir().resolve("eu-west"), TOKEN);

        assertEquals(Optional.empty(), PeerCredentials.bearerToken(Optional.empty(), peer("eu-west")));
        assertEquals(Optional.empty(), PeerCredentials.bearerToken(Optional.empty(), peer("missing")));
    }

    @Test
    void aPeerWithoutAReferenceIsSearchedAnonymously() throws IOException {
        assertEquals(Optional.empty(), token(credentialsDir(), null));
    }

    @Test
    void readsTheKeyNamedByTheReferenceWithoutTheTrailingLineBreak() throws IOException {
        Path dir = credentialsDir();
        Files.writeString(dir.resolve("eu-west"), TOKEN + "\n");

        assertEquals(Optional.of(TOKEN), token(dir, "eu-west"));
    }

    @Test
    void aMissingKeyFailsTheCallInsteadOfSearchingAnonymously() throws IOException {
        assertEquals("The peer's credential was not found in the credentials directory.",
                unavailable(credentialsDir(), "eu-west"));
    }

    @Test
    void anUnreadableKeyFailsTheCall() throws IOException {
        Path dir = credentialsDir();
        Path file = Files.writeString(dir.resolve("eu-west"), TOKEN);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        // Root reads any file, whatever its permissions.
        assumeTrue(!Files.isReadable(file), "Running as a user that can read any file");

        assertEquals("The peer's credential could not be read.", unavailable(dir, "eu-west"));
    }

    @Test
    void anEmptyOrMalformedCredentialFailsTheCall() throws IOException {
        Path dir = credentialsDir();
        Files.writeString(dir.resolve("empty"), " \n");
        Files.writeString(dir.resolve("two-lines"), TOKEN + "\nX-Injected: yes");
        Files.writeString(dir.resolve("spaces"), "Bearer " + TOKEN);
        Files.write(dir.resolve("binary"), new byte[] { 'a', 0, 'b' });
        Files.writeString(dir.resolve("large"), "a".repeat(PeerCredentials.MAX_CREDENTIAL_BYTES + 1));
        Files.createDirectory(dir.resolve("directory"));

        assertEquals("The peer's credential is empty.", unavailable(dir, "empty"));
        assertEquals("The peer's credential is not a valid bearer token.", unavailable(dir, "two-lines"));
        assertEquals("The peer's credential is not a valid bearer token.", unavailable(dir, "spaces"));
        assertEquals("The peer's credential is not a valid bearer token.", unavailable(dir, "binary"));
        assertEquals("The peer's credential is larger than 16384 bytes.", unavailable(dir, "large"));
        assertEquals("The peer's credential is not a file.", unavailable(dir, "directory"));
    }

    @Test
    void aReferenceCannotLeaveTheDirectory() throws IOException {
        Path dir = credentialsDir();
        Files.writeString(root.resolve("outside"), TOKEN);
        Files.createDirectory(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub").resolve("key"), TOKEN);

        String notAKey = "The peer's credential reference does not name a key in the credentials directory.";
        assertEquals(notAKey, unavailable(dir, "../outside"));
        assertEquals(notAKey, unavailable(dir, ".."));
        assertEquals(notAKey, unavailable(dir, "."));
        assertEquals(notAKey, unavailable(dir, "sub/key"));
        assertEquals(notAKey, unavailable(dir, "sub/../../outside"));
        assertEquals(notAKey, unavailable(dir, ""));
        assertEquals(notAKey, unavailable(dir, root.resolve("outside").toString()));
    }

    @Test
    void aKeyThatLinksOutOfTheDirectoryIsRefused() throws IOException {
        Path dir = credentialsDir();
        Path outside = Files.writeString(root.resolve("outside"), TOKEN);
        Files.createSymbolicLink(dir.resolve("eu-west"), outside);

        assertEquals("The peer's credential is outside the credentials directory.", unavailable(dir, "eu-west"));
    }

    @Test
    void readsAKubernetesSecretVolumeAndPicksUpARotatedKey() throws IOException {
        // The layout the kubelet writes: each key links to ..data/<key>, and ..data links to a
        // timestamped directory, which an update replaces by switching ..data atomically.
        Path dir = credentialsDir();
        Path first = Files.createDirectory(dir.resolve("..2026_11_12_10_00_00.000000001"));
        Files.writeString(first.resolve("eu-west"), "first-token\n");
        Files.createSymbolicLink(dir.resolve("..data"), first.getFileName());
        Files.createSymbolicLink(dir.resolve("eu-west"), Path.of("..data", "eu-west"));

        assertEquals(Optional.of("first-token"), token(dir, "eu-west"));

        Path second = Files.createDirectory(dir.resolve("..2026_11_12_11_00_00.000000002"));
        Files.writeString(second.resolve("eu-west"), "second-token\n");
        Path newData = Files.createSymbolicLink(dir.resolve("..data_tmp"), second.getFileName());
        Files.move(newData, dir.resolve("..data"), StandardCopyOption.ATOMIC_MOVE);

        assertEquals(Optional.of("second-token"), token(dir, "eu-west"));
    }

    @Test
    void theCredentialsDirectoryMayItselfBeALink() throws IOException {
        Path dir = credentialsDir();
        Files.writeString(dir.resolve("eu-west"), TOKEN);
        Path link = Files.createSymbolicLink(root.resolve("credentials-link"), dir);

        assertEquals(Optional.of(TOKEN), token(link, "eu-west"));
    }

    @Test
    void theCredentialIsReadAsUsAscii() throws IOException {
        Path dir = credentialsDir();
        Files.write(dir.resolve("eu-west"), "tökén".getBytes(StandardCharsets.UTF_8));

        assertEquals("The peer's credential is not a valid bearer token.", unavailable(dir, "eu-west"));
    }
}
