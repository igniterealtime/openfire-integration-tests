# Openfire Integration Tests

Integration tests for Openfire XMPP server, focusing on federation scenarios. These tests verify server-to-server (S2S) communication, user authentication, and message routing in a federated environment.

## Test Infrastructure

These tests run against a federated Openfire environment provided by the [openfire-docker-compose](https://github.com/surevine/openfire-docker-compose) project, which is included as a Git submodule. That project provides:

- A complete Docker-based federation environment
- Network configuration for server-to-server testing
- Certificate management infrastructure
- Pre-configured users and databases

For full details of the test infrastructure, see the [openfire-docker-compose documentation](https://github.com/surevine/openfire-docker-compose/blob/main/federation/README.md).

## Prerequisites

- Java 17 or later
- Docker and Docker Compose

### Building the Openfire Image

The docker-compose-based test environment [currently requires a local Openfire Docker image](https://github.com/surevine/openfire-docker-compose/issues/77) tagged as `openfire:latest`. To build this:

1. Clone the Openfire repository:
```bash
git clone https://github.com/igniterealtime/Openfire.git
cd Openfire
```

2. Build the Docker image:
```bash
docker build -t openfire:latest .
```

## Setup

1. Clone this repository:
```bash
git clone https://github.com/surevine/openfire-integration-tests.git
cd openfire-integration-tests
```

2. Initialise and update the openfire-docker-compose submodule:
```bash
git submodule update --init
```

## Running Tests

The project uses Maven Wrapper (no local Maven installation required).

### Running All Tests
```bash
./mvnw verify
```

### Running Specific Tests
```bash
# Run a single test class
./mvnw verify -Dit.test=ConnectAndAuthenticateIT

# Run a specific test method
./mvnw verify -Dit.test=FederatedChatIT#federatedMessageTest
```

### Server-to-Server Configurations

`./mvnw verify` runs the tests in three configurations, each on a fresh federated environment:

- **Dialback** (Failsafe execution `dialback`): server-to-server TLS is disabled, so the servers authenticate to each
  other with dialback.
- **SASL EXTERNAL** (Failsafe execution `sasl-external`): TLS is enabled and dialback disabled, so the servers
  authenticate with the certificates of the federation environment. `xmpp.server.outgoing.accept-reused-stream-id` is
  false on both servers, so this run also fails if a server does not send a new stream ID when the stream is restarted
  after SASL.
- **Stream Management** (Failsafe execution `stream-management`): SASL EXTERNAL as above, with Stream Management
  (XEP-0198) and resumption enabled on server-to-server connections. Besides the federation tests, it runs
  `StreamManagementFederationIT`, which tests that a stream is resumed after its connection breaks, and that every
  message then arrives once, in order. That test is skipped against Openfire versions before 5.2.0, which don't have
  Stream Management on server-to-server connections (OF-2425).

Each run writes its test reports, and the Openfire logs of both servers (`1-openfire.log`, `2-openfire.log`), to its own
directory: `target/failsafe-reports/dialback`, `target/failsafe-reports/sasl-external` and
`target/failsafe-reports/stream-management`.

### Configuring Openfire Version

By default, the tests will use the `latest` tag of the Openfire Docker image. You can specify a different version by setting the `OPENFIRE_TAG` environment variable:

```bash
# Run tests with a specific Openfire image tag
OPENFIRE_TAG=4.7.5 ./mvnw verify

# Or set for your shell session
export OPENFIRE_TAG=4.7.5
./mvnw verify
```

Make sure you have built the Openfire image with the corresponding tag:
```bash
docker build -t openfire:4.7.5 .
```

### Test Users

The test environment comes with pre-configured users:

Server 1 (xmpp1.localhost.example):
- user1/password
- user2/password

Server 2 (xmpp2.localhost.example):
- user3/password
- user4/password

## Development

### Adding a Test

- **Choose the configurations with JUnit tags.** A test tagged `federation` (a test of stanzas sent between the
  servers) runs in all three configurations. A test tagged `stream-management` as well runs only in the Stream
  Management configuration. A test without a tag runs only with dialback. No change to `pom.xml` is needed.
- **Skip a test of a feature that older Openfire versions don't have.** Openfire's maintenance branches (5.0 and 5.1)
  run this repository's tests in their CI too, so such a test must skip itself on those versions, with the reason, as
  `StreamManagementFederationIT` does: it checks the version that the server reports (XEP-0092).
- **Breaking connections between the servers.** In the Stream Management configuration, each server reaches the other
  through a relay: a [Toxiproxy](https://github.com/Shopify/toxiproxy) container (`openfire-s2s-relay`), which joins the
  network of the `xmpp1` container as `s2s-relay` and reaches the servers by their Compose service names.
  `FederatedTestEnvironment` defines its settings, and lets a test make it drop what it relays
  (`dropRelayed`) or break the connections through it (`breakRelayedConnections`).


### Keeping Up to Date

1. Pull latest changes:
```bash
# Update main repository
git pull

# Update openfire-docker-compose submodule to latest version
git submodule update --remote
```

2. When switching branches or updating submodule reference:
```bash
git submodule update --init
```

## License

This project is licensed under the same terms as Openfire. See [Openfire's license](https://github.com/igniterealtime/Openfire/blob/main/LICENSE.txt) for details.