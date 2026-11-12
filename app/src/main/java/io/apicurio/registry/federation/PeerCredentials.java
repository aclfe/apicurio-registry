package io.apicurio.registry.federation;

import io.apicurio.registry.storage.dto.PeerDto;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads the credential a peer registry is searched with.
 * <p>
 * Peer credentials are off unless the administrator configures a credentials directory. Then a
 * peer with a credential reference is searched with the content of the file of that name in the
 * directory, as a bearer token, and a peer without one is still searched anonymously. Either way
 * the search asks for public agents only, so a credential never widens what a peer returns.
 * <p>
 * The file is read on every call, so that a rotated credential is picked up. That includes a
 * Kubernetes Secret volume, whose keys are symbolic links into a directory the kubelet swaps: the
 * file may be a link, but it must resolve to a place inside the credentials directory.
 * <p>
 * A configured credential that cannot be used fails the call, and the peer is never searched
 * anonymously instead. The credential is never logged or put in an exception message.
 */
final class PeerCredentials {

    /** Far more than a JSON Web Token needs, and small enough to read on every call. */
    static final int MAX_CREDENTIAL_BYTES = 16 * 1024;

    /** The token68 syntax of RFC 9110, which a bearer token follows. Nothing else goes in the header. */
    private static final Pattern BEARER_TOKEN = Pattern.compile("[A-Za-z0-9._~+/-]+=*");

    private PeerCredentials() {
    }

    /**
     * @param directory the configured credentials directory, if any
     * @return the bearer token to search the peer with, or empty to search it anonymously
     * @throws PeerSearchException.CredentialUnavailable if the peer has a credential reference and
     *             credentials are enabled, but the credential cannot be used
     */
    static Optional<String> bearerToken(Optional<Path> directory, PeerDto peer) {
        String reference = peer.getCredentialSecretRef();
        if (directory.isEmpty() || reference == null) {
            return Optional.empty();
        }
        Path dir = directory.get();
        Path file = dir.resolve(reference).normalize();
        // The reference was validated when the peer was written; checked again, since nothing else
        // stops a reference from leaving the directory.
        if (reference.isEmpty() || !dir.normalize().equals(file.getParent())
                || !reference.equals(file.getFileName().toString())) {
            throw unavailable("The peer's credential reference does not name a key in the credentials directory.");
        }

        Path realFile;
        try {
            Path realDir = dir.toRealPath();
            realFile = file.toRealPath();
            if (!realFile.startsWith(realDir)) {
                throw unavailable("The peer's credential is outside the credentials directory.");
            }
        } catch (NoSuchFileException ex) {
            throw unavailable("The peer's credential was not found in the credentials directory.");
        } catch (IOException ex) {
            throw unavailable("The peer's credential could not be read.");
        }
        if (!Files.isRegularFile(realFile)) {
            throw unavailable("The peer's credential is not a file.");
        }

        byte[] content;
        try (InputStream in = Files.newInputStream(realFile)) {
            content = in.readNBytes(MAX_CREDENTIAL_BYTES + 1);
        } catch (IOException ex) {
            throw unavailable("The peer's credential could not be read.");
        }
        if (content.length > MAX_CREDENTIAL_BYTES) {
            throw unavailable("The peer's credential is larger than " + MAX_CREDENTIAL_BYTES + " bytes.");
        }
        // A file written by hand, or by kubectl from a literal, often ends with a line break.
        String token = new String(content, StandardCharsets.US_ASCII).strip();
        if (token.isEmpty()) {
            throw unavailable("The peer's credential is empty.");
        }
        if (!BEARER_TOKEN.matcher(token).matches()) {
            throw unavailable("The peer's credential is not a valid bearer token.");
        }
        return Optional.of(token);
    }

    private static PeerSearchException.CredentialUnavailable unavailable(String message) {
        return new PeerSearchException.CredentialUnavailable(message);
    }
}
