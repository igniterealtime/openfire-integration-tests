# Openfire Integration Tests

Integration tests for Openfire, focusing on federation scenarios.

## Prerequisites

- Java 17
- Docker and Docker Compose

The [openfire-docker-compose](https://github.com/surevine/openfire-docker-compose) system used by these tests expects 
to find an image tagged as openfire:latest locally. To build one, from 
the [openfire](https://github.com/igniterealtime/Openfire) project root:

```bash
docker build -t openfire:latest .
```

## Setup

1. Clone this repository:
```bash
git clone https://github.com/surevine/openfire-integration-tests.git
cd openfire-integration-tests
```

2. Initialize and update the submodule:
```bash
git submodule update --init
```

## Running Tests

The project uses Maven Wrapper, so no local Maven installation is required.

Run all integration tests:
```bash
./mvnw verify
```

Run a specific test:
```bash
./mvnw verify -Dit.test=ConnectAndAuthenticateIT
```

## Test Environment

The tests use the Openfire Docker Compose environment from the `openfire-docker-compose` repository (included as a submodule).

The environment provides:
- Two Openfire instances:
    - xmpp1 (ports: 5221, 7071, 9091)
    - xmpp2 (ports: 5222, 7072, 9092)
- PostgreSQL databases for each instance
- Pre-configured users:
    - xmpp1: user1/password, user2/password
    - xmpp2: user3/password, user4/password
- Network configuration for federation

## Development

### Keeping Up to Date

1. Pull latest changes from both repositories:
```bash
git pull
git submodule update --remote
```

2. If switching branches or updating submodule reference:
```bash
git submodule update --init
```