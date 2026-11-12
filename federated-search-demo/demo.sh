#!/usr/bin/env bash
# Federated agent search across Apicurio Registry instances: end-to-end demo.
#
# Starts a coordinating registry ("local") and five peers on one Docker network, registers the
# peers, and runs one federated search that shows every kind of source outcome. Then shows the
# hop refusal, the perSourceLimit check, and that the peer API never returns a credential.
#
# Needs Docker, curl and jq. Leaves the containers running for the UI part of the demo; run
# "./demo.sh clean" to remove them.
#
#   IMAGE          the registry image to demo (built from the branch)
#   LEGACY_IMAGE   a released registry without the public-only mode
set -euo pipefail

IMAGE=${IMAGE:-apicurio/apicurio-registry:fed-demo-d4e30db17}
LEGACY_IMAGE=${LEGACY_IMAGE:-quay.io/apicurio/apicurio-registry:latest-release}
NET=fed-demo
CREDS=${CREDS:-$HOME/.cache/fed-demo-credentials}
LOCAL=http://localhost:8080
REMOTE=http://localhost:8081
LEGACY=http://localhost:8084
SKILL=translate

clean() {
    docker rm -f fed-demo-local fed-demo-remote fed-demo-slow fed-demo-down fed-demo-legacy >/dev/null 2>&1 || true
    docker network rm $NET >/dev/null 2>&1 || true
}

if [ "${1:-}" = "clean" ]; then
    clean
    exit 0
fi

say() { printf '\n### %s\n' "$*"; }
run() { printf '$ %s\n' "$*"; eval "$@"; printf '\n'; }

card() { # registry, artifact id, visibility label or "none"
    local labels='{}'
    [ "$3" != "none" ] && labels="{\"apicurio.agent.visibility\": \"$3\"}"
    curl -sf -o /dev/null -X POST "$1/apis/registry/v3/groups/demo/artifacts" -H 'Content-Type: application/json' -d "{
      \"artifactId\": \"$2\", \"artifactType\": \"AGENT_CARD\", \"labels\": $labels,
      \"firstVersion\": {\"content\": {\"contentType\": \"application/json\", \"content\": $(jq -Rs . <<<"{
        \"name\": \"$2\", \"description\": \"Demo agent $2\", \"version\": \"1.0.0\",
        \"supportedInterfaces\": [{\"url\": \"https://example.com/$2\", \"protocolBinding\": \"http+json\", \"protocolVersion\": \"1.0\"}],
        \"capabilities\": {\"streaming\": false}, \"skills\": [{\"id\": \"$SKILL\", \"name\": \"Translate\", \"description\": \"x\", \"tags\": [\"t\"]}],
        \"defaultInputModes\": [\"text\"], \"defaultOutputModes\": [\"text\"]}")}}}"
}

wait_up() {
    for _ in $(seq 1 90); do
        curl -sf -o /dev/null "$1/apis/registry/v3/system/info" && return 0
        sleep 2
    done
    echo "$1 did not start" >&2
    exit 1
}

clean
docker network create $NET >/dev/null
mkdir -p "$CREDS"
# A peer's credential is one bearer token in a file named after its key. This demo's remote
# registry has authentication off, so it ignores the token; the auth-enabled case is in
# FederatedSearchAuthMultiInstanceIT.
printf 'demo-token\n' > "$CREDS/remote-token"

PEER_ENV=(-e APICURIO_FEATURES_EXPERIMENTAL_ENABLED=true -e APICURIO_A2A_ENABLED=true)
docker run -d --name fed-demo-remote --network $NET --network-alias remote -p 8081:8080 "${PEER_ENV[@]}" "$IMAGE" >/dev/null
docker run -d --name fed-demo-slow --network $NET --network-alias slow "${PEER_ENV[@]}" "$IMAGE" >/dev/null
docker run -d --name fed-demo-down --network $NET --network-alias down "${PEER_ENV[@]}" "$IMAGE" >/dev/null
docker run -d --name fed-demo-legacy --network $NET --network-alias legacy -p 8084:8080 "${PEER_ENV[@]}" "$LEGACY_IMAGE" >/dev/null
docker run -d --name fed-demo-local --network $NET -p 8080:8080 -p 19000:9000 "${PEER_ENV[@]}" \
    -e APICURIO_FEDERATION_ENABLED=true \
    -e APICURIO_FEDERATION_PEER_INSECURE_HTTP_ENABLED=true \
    -e APICURIO_FEDERATION_PEER_CREDENTIALS_DIR=/peer-credentials \
    -e APICURIO_UI_FEATURES_AGENTS_ENABLED=true \
    -v "$CREDS":/peer-credentials:ro \
    "$IMAGE" >/dev/null
wait_up $LOCAL
wait_up $REMOTE
wait_up $LEGACY

say "Image under demo"
run "docker image inspect --format '{{.Id}}' $IMAGE"
run "docker image inspect --format '{{.Id}}' $LEGACY_IMAGE"
run "curl -s '$LEGACY/apis/registry/v3/system/info' | jq -c '{name, version}'"

say "Agent cards: one public card here, and three cards on the remote registry, only one of them public"
card $LOCAL local-translator public
card $REMOTE remote-translator public
card $REMOTE remote-private-translator private
card $REMOTE remote-unlabelled-translator none
card $LEGACY legacy-translator public || echo "(the released registry did not take the agent card)"
run "curl -s '$REMOTE/.well-known/agents?skill=$SKILL' | jq -c '[.agents[].artifactId]'"

say "Peers: six registrations of five registries"
peer() { curl -sf -o /dev/null -X POST $LOCAL/apis/registry/v3/admin/peers -H 'Content-Type: application/json' -d "$1"; }
peer '{"peerId": "remote", "url": "http://remote:8080", "name": "Remote registry"}'
peer '{"peerId": "remote-credentialed", "url": "http://remote:8080", "name": "Remote registry, with a credential", "credentialSecretRef": "remote-token"}'
peer '{"peerId": "remote-missing-key", "url": "http://remote:8080", "name": "Remote registry, credential missing", "credentialSecretRef": "no-such-token"}'
peer '{"peerId": "slow", "url": "http://slow:8080", "name": "Paused registry"}'
peer '{"peerId": "down", "url": "http://down:8080", "name": "Stopped registry"}'
peer '{"peerId": "legacy", "url": "http://legacy:8080", "name": "Released registry without the public-only mode"}'
run "curl -s '$LOCAL/apis/registry/v3/admin/peers' | jq -c '.peers[] | {peerId, url, enabled, credentialSecretRef}'"

say "The peer API stores and returns the reference, never the credential"
run "curl -s '$LOCAL/apis/registry/v3/admin/peers/remote-credentialed' | jq -c . ; curl -s '$LOCAL/apis/registry/v3/admin/peers' | grep -c demo-token || true"

say "One registry is paused and one is stopped"
docker pause fed-demo-slow >/dev/null
docker stop fed-demo-down >/dev/null
echo "paused fed-demo-slow, stopped fed-demo-down"

say "Federated search: every source has its own outcome, and the answer comes within the 5 s deadline"
run "curl -s -o /tmp/fed-demo-search.json -w 'HTTP %{http_code} in %{time_total} s\n' '$LOCAL/apis/registry/v3/search/federated?skill=$SKILL'"
run "jq -c '.sources[]' /tmp/fed-demo-search.json"
run "jq -c '.agents[] | {source, artifactId, owner}' /tmp/fed-demo-search.json"

say "A search that another registry forwarded is refused, so a search goes at most one hop"
run "curl -s -H 'X-Federation-Hop: 1' '$LOCAL/apis/registry/v3/search/federated' | jq -c '{status, title}'"

say "perSourceLimit below 1 is refused"
run "curl -s '$LOCAL/apis/registry/v3/search/federated?perSourceLimit=0' | jq -c '{status, title}'"

say "Per-peer metrics, with the outcome and reason of every call"
run "curl -s http://localhost:19000/metrics | grep -E '^apicurio_federation_peer_search_seconds_count' | sort"

docker unpause fed-demo-slow >/dev/null
echo
echo "Done. The containers are still running for the UI; './demo.sh clean' removes them."
