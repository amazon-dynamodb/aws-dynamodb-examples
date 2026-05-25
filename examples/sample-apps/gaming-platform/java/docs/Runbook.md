# Runbook - DynamoDB Sample App - Gaming Platform

## Prerequisites


| Need                            | When                                                                                         |
|---------------------------------| -------------------------------------------------------------------------------------------- |
| **JDK 21**                      | Build and run on the host                                                                    |
| **Maven 3.6.3+**                | Compile, run tests, and package the app (`./scripts/build-app.sh` or `mvn`)                  |
| **Docker** (or Rancher Desktop) | DynamoDB Local, full Docker run, or integration/smoke tests                                  |
| **AWS credentials**             | Only when pointing the app at **real** AWS DynamoDB (env, `~/.aws/credentials`, or IAM role) |


## Tech stack

- **Runtime:** JDK 21, Spring Boot **3.5.13**
- **AWS:** SDK for Java v2 BOM **2.42.21** (`dynamodb`, `dynamodb-enhanced`)
- **API docs:** springdoc-openapi **2.8.16**
- **Tests:** JUnit 5 (via Spring Boot), **Testcontainers 2.0.3** (DynamoDB Local for integration/smoke)
- **Datastore:** DynamoDB Local (Docker) or AWS DynamoDB

## Quick start

### Build

Compiles the project and runs unit tests.

```bash
./scripts/build-app.sh
```

### Run (three ways)

All modes use three tables (**GamingPlayerState**, **GamingGameEvents**, **GamingLeaderboard** by default). On startup the app **creates tables** if missing, enables **DynamoDB Streams** and **TTL** on **GameEvents**, starts the **streams consumer** for leaderboard projection, and **idempotently seeds** five demo players. Existing seed rows are not overwritten.

---

**1) Host app + DynamoDB Local (Docker)**

*Best for local debugging: JVM on your machine, database in a container.*

```bash
./scripts/start-dynamodb-local.sh
./scripts/run-app-local.sh
# ./scripts/stop-dynamodb-local.sh   # stop only DynamoDB Local. App stops independently (Ctrl+C)
```

<details>
<summary>Command format & arguments</summary>

```bash
./scripts/run-app-local.sh [--dynamodb-endpoint <url>] [--dynamodb-region <region>] [--dynamodb-client-type <type>]
```

| Option (`run-app-local.sh`)     | Default                 | Notes                                              |
| ------------------------------- | ----------------------- | -------------------------------------------------- |
| `--dynamodb-endpoint <url>`     | `http://localhost:8000` | Local or `https://dynamodb.<region>.amazonaws.com` |
| `--dynamodb-region <region>`    | `eu-west-1`             | Must match the AWS endpoint when not local         |
| `--dynamodb-client-type <type>` | `high-level`            | `high-level` (enhanced client) or `low-level`      |

</details>




---

**2) Full stack in Docker (app + DynamoDB Local)**

*Best when you don’t want JDK/Maven on the host. Compose builds the app image and starts both services.*

```bash
./scripts/run-app-docker.sh
./scripts/run-app-docker.sh --stop    # compose down for profile app
```

<details>
<summary>Command format & arguments</summary>

```bash
./scripts/run-app-docker.sh [--stop] [--dynamodb-client-type <type>]
```

| Option (`run-app-docker.sh`)    | Default      | Notes                                                     |
| ------------------------------- | ------------ | --------------------------------------------------------- |
| `--stop`                        | -            | Stops the Compose stack for profile `app` and exits       |
| `--dynamodb-client-type <type>` | `high-level` | Passed through as `DYNAMODB_CLIENTTYPE` for the container |

</details>




---

**3) Host app + AWS DynamoDB**

*Best for a real account/region. No Docker required for the database.*

- Set **AWS credentials** and pass the **regional HTTPS endpoint** so the SDK hits your tables in that region.

```bash
./scripts/run-app-local.sh \
  --dynamodb-endpoint https://dynamodb.eu-west-1.amazonaws.com \
  --dynamodb-region eu-west-1
```

<details>
<summary>Command format & arguments</summary>

```bash
./scripts/run-app-local.sh [--dynamodb-endpoint <url>] [--dynamodb-region <region>] [--dynamodb-client-type <type>]
```

| Option (`run-app-local.sh`)     | Default                 | Notes                                                 |
| ------------------------------- | ----------------------- | ----------------------------------------------------- |
| `--dynamodb-endpoint <url>`     | `http://localhost:8000` | Use `https://dynamodb.<region>.amazonaws.com` for AWS |
| `--dynamodb-region <region>`    | `eu-west-1`             | Must match the endpoint region                        |
| `--dynamodb-client-type <type>` | `high-level`            | `high-level` (enhanced client) or `low-level`         |

</details>




### API documentation

With the app up: **Swagger UI** → [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
