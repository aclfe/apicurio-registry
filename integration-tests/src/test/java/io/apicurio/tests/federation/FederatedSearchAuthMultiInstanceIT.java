package io.apicurio.tests.federation;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static io.apicurio.deployment.Constants.FEDERATION;
import static io.apicurio.tests.kafkasql.startup.infra.Configs.REGISTRY_V3_IMAGE;
import static io.apicurio.tests.kafkasql.startup.infra.Configs.REGISTRY_V3_IMAGE_DEFAULT;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Federated agent search between two registries that both have authentication and role-based
 * authorization on, with Keycloak issuing the tokens. Deploys its own containers on a private
 * network; it does not use the matrix-deployed registry.
 * <p>
 * The {@code local} registry has the {@code remote} one registered four times, so that one search
 * shows every way a peer can be called: anonymously, with a working credential, with a credential
 * the remote registry rejects, and with a credential that is missing from the credentials
 * directory. The working credential is a token of an administrator of the remote registry, who may
 * read every agent there, which is what shows that the search stays public-only with a credential.
 */
@Tag(FEDERATION)
public class FederatedSearchAuthMultiInstanceIT {

    private static final Logger log = LoggerFactory.getLogger(FederatedSearchAuthMultiInstanceIT.class);

    private static final String KEYCLOAK_ALIAS = "keycloak";
    private static final String REMOTE_ALIAS = "remote-registry";
    private static final String CREDENTIALS_DIR = "/tmp/peer-credentials";
    private static final String SKILL = "auth-multi-instance-search";
    private static final String VISIBILITY_LABEL = "apicurio.agent.visibility";

    private static final String CARD = """
            {
                "name": "%s",
                "description": "An agent for the auth-enabled multi-instance federated search test",
                "version": "1.0.0",
                "supportedInterfaces": [
                    { "url": "https://example.com/agent", "protocolBinding": "http+json", "protocolVersion": "1.0" }
                ],
                "capabilities": { "streaming": false, "pushNotifications": false },
                "skills": [ { "id": "%s", "name": "Search", "description": "A test skill", "tags": ["t"] } ],
                "defaultInputModes": ["text"],
                "defaultOutputModes": ["text"]
            }
            """;

    private static Network network;
    private static KeycloakContainer keycloak;
    private static GenericContainer<?> remote;
    private static GenericContainer<?> local;

    @BeforeAll
    static void startRegistries() {
        network = Network.newNetwork();
        keycloak = new KeycloakContainer(DockerImageName.parse("quay.io/keycloak/keycloak").withTag("26.1.0").toString())
                .withRealmImportFile("/realm.json")
                .withNetwork(network)
                .withNetworkAliases(KEYCLOAK_ALIAS);
        keycloak.start();

        remote = registry(Map.of()).withNetworkAliases(REMOTE_ALIAS);
        local = registry(Map.of(
                "APICURIO_FEDERATION_ENABLED", "true",
                // The peer is reached over plain http on the container network.
                "APICURIO_FEDERATION_PEER_INSECURE_HTTP_ENABLED", "true",
                "APICURIO_FEDERATION_PEER_CREDENTIALS_DIR", CREDENTIALS_DIR,
                // The rejected credential is called on every search. 401 does not count against a
                // peer, but keep the breaker out of the picture anyway.
                "APICURIO_FEDERATION_SEARCH_BREAKER_REQUEST_VOLUME_THRESHOLD", "100"))
                // The directory must exist at startup; the credentials are written into it later.
                .withCopyToContainer(Transferable.of("not a credential"), CREDENTIALS_DIR + "/placeholder");
        Startables.deepStart(remote, local).join();
        remote.followOutput(new Slf4jLogConsumer(log).withPrefix("remote"));
        local.followOutput(new Slf4jLogConsumer(log).withPrefix("local"));

        String admin = token("admin-client");
        createAgent(local, admin, "local-public-agent", "public");
        createAgent(local, admin, "local-private-agent", "private");
        createAgent(remote, admin, "remote-public-agent", "public");
        createAgent(remote, admin, "remote-private-agent", "private");
        // No label: the default visibility is entitled, which any authenticated caller of the
        // remote registry may read, but which is not public.
        createAgent(remote, admin, "remote-entitled-agent", null);

        String remoteUrl = "http://" + REMOTE_ALIAS + ":8080";
        registerPeer(admin, Map.of("peerId", "a-anonymous", "url", remoteUrl));
        registerPeer(admin, Map.of("peerId", "b-rejected", "url", remoteUrl, "credentialSecretRef", "rejected"));
        registerPeer(admin, Map.of("peerId", "c-credentialed", "url", remoteUrl, "credentialSecretRef", "remote-admin"));
        registerPeer(admin, Map.of("peerId", "d-missing", "url", remoteUrl, "credentialSecretRef", "absent"));
        writeCredential("rejected", "not-a-token-the-remote-registry-accepts");
    }

    @AfterAll
    static void stopRegistries() {
        if (local != null) {
            local.stop();
        }
        if (remote != null) {
            remote.stop();
        }
        if (keycloak != null) {
            keycloak.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    @Test
    void aCallerWithoutCredentialsCannotSearch() {
        given().queryParam("skill", SKILL).get(searchUrl()).then().statusCode(401);
    }

    @Test
    void aCredentialedPeerStillReturnsOnlyItsPublicAgents() {
        writeCredential("remote-admin", token("admin-client"));
        // The remote administrator the credential belongs to can read all three remote agents.
        given().auth().oauth2(token("admin-client")).queryParam("skill", SKILL)
                .get(url(remote) + "/.well-known/agents").then().statusCode(200)
                .body("agents.artifactId", containsInAnyOrder("remote-public-agent", "remote-private-agent",
                        "remote-entitled-agent"));

        searchAs("admin-client")
                .body("sources.source", contains("local", "a-anonymous", "b-rejected", "c-credentialed", "d-missing"))
                .body("sources.outcome", contains("ok", "ok", "failed", "ok", "failed"))
                .body("sources[2].reason", equalTo("unauthorized"))
                .body("sources[4].reason", equalTo("credential_unavailable"))
                .body("agents.findAll { it.source == 'a-anonymous' }.artifactId", contains("remote-public-agent"))
                .body("agents.findAll { it.source == 'c-credentialed' }.artifactId", contains("remote-public-agent"))
                // This registry's own results follow the caller's visibility: the administrator
                // sees the private local agent too.
                .body("agents.findAll { it.source == 'local' }.artifactId",
                        containsInAnyOrder("local-public-agent", "local-private-agent"));
    }

    @Test
    void aReaderSeesTheSamePublicRemoteAgentsWithThePeersNumberedAndNoReasons() {
        writeCredential("remote-admin", token("admin-client"));

        searchAs("readonly-client")
                .body("sources.source", contains("local", "peer-1", "peer-2", "peer-3", "peer-4"))
                .body("sources.outcome", contains("ok", "ok", "failed", "ok", "failed"))
                .body("sources[2].reason", nullValue())
                .body("sources[4].reason", nullValue())
                .body("agents.findAll { it.source == 'peer-1' }.artifactId", contains("remote-public-agent"))
                .body("agents.findAll { it.source == 'peer-3' }.artifactId", contains("remote-public-agent"))
                .body("agents.findAll { it.source == 'local' }.artifactId", contains("local-public-agent"));
    }

    @Test
    void aReplacedCredentialIsUsedFromTheNextSearch() {
        writeCredential("remote-admin", "a-token-the-remote-registry-does-not-accept");
        searchAs("admin-client").body("sources[3].source", equalTo("c-credentialed"))
                .body("sources[3].outcome", equalTo("failed"))
                .body("sources[3].reason", equalTo("unauthorized"));

        writeCredential("remote-admin", token("admin-client"));
        searchAs("admin-client").body("sources[3].outcome", equalTo("ok"))
                .body("agents.findAll { it.source == 'c-credentialed' }.artifactId", contains("remote-public-agent"));
    }

    private static ValidatableResponse searchAs(String clientId) {
        return given().auth().oauth2(token(clientId)).queryParam("skill", SKILL).get(searchUrl()).then()
                .statusCode(200);
    }

    private static String searchUrl() {
        return url(local) + "/apis/registry/v3/search/federated";
    }

    /** A token of the client's service account; the realm gives every one a secret of test1. */
    private static String token(String clientId) {
        return given().formParam("grant_type", "client_credentials").formParam("client_id", clientId)
                .formParam("client_secret", "test1")
                .post(keycloak.getAuthServerUrl() + "/realms/registry/protocol/openid-connect/token")
                .then().statusCode(200).extract().path("access_token");
    }

    private static void writeCredential(String key, String content) {
        local.copyFileToContainer(Transferable.of(content + "\n"), CREDENTIALS_DIR + "/" + key);
    }

    private static void registerPeer(String adminToken, Map<String, String> peer) {
        given().auth().oauth2(adminToken).contentType(ContentType.JSON).body(peer)
                .post(url(local) + "/apis/registry/v3/admin/peers").then().statusCode(204);
    }

    private static GenericContainer<?> registry(Map<String, String> env) {
        Map<String, String> allEnv = new HashMap<>(env);
        // Federation and the agent registry are experimental features.
        allEnv.put("APICURIO_FEATURES_EXPERIMENTAL_ENABLED", "true");
        allEnv.put("APICURIO_A2A_ENABLED", "true");
        allEnv.put("QUARKUS_OIDC_TENANT_ENABLED", "true");
        allEnv.put("QUARKUS_OIDC_AUTH_SERVER_URL", "http://" + KEYCLOAK_ALIAS + ":8080/realms/registry");
        // The tests take their tokens from Keycloak's mapped port on the host, so the issuer in
        // them is not the address the registries know Keycloak by.
        allEnv.put("QUARKUS_OIDC_TOKEN_ISSUER", "any");
        allEnv.put("APICURIO_AUTH_ROLE_BASED_AUTHORIZATION", "true");
        return new GenericContainer<>(System.getProperty(REGISTRY_V3_IMAGE, REGISTRY_V3_IMAGE_DEFAULT))
                .withEnv(allEnv)
                .withNetwork(network)
                // 8080 serves the API, 9000 is the management interface with the health checks.
                .withExposedPorts(8080, 9000)
                .waitingFor(Wait.forHttp("/health/ready").forPort(9000).forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(120)));
    }

    private static String url(GenericContainer<?> registry) {
        return "http://" + registry.getHost() + ":" + registry.getMappedPort(8080);
    }

    private static void createAgent(GenericContainer<?> registry, String adminToken, String artifactId,
            String visibility) {
        Map<String, Object> body = new HashMap<>();
        body.put("artifactId", artifactId);
        body.put("artifactType", "AGENT_CARD");
        if (visibility != null) {
            body.put("labels", Map.of(VISIBILITY_LABEL, visibility));
        }
        body.put("firstVersion", Map.of("content",
                Map.of("content", CARD.formatted(artifactId, SKILL), "contentType", "application/json")));

        given().auth().oauth2(adminToken).contentType(ContentType.JSON).body(body)
                .post(url(registry) + "/apis/registry/v3/groups/federation-auth-it/artifacts")
                .then().statusCode(200);
    }
}
