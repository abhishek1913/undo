# ADR-000: Pinned versions

**Context.** Section 28.4 gives baselines and asks to pin the latest patch at M0. Pinned on 2026-10-24
from Maven Central and the npm registry.

**Decision.**

| Area | Library | Pinned |
|---|---|---|
| Runtime | Java (Temurin) | 21.0.9 |
| Framework | Spring Boot | 3.5.16 (latest 3.5.x; 4.x not used, see ADR-012) |
| DB | PostgreSQL / Flyway / HikariCP / pgjdbc | 17 / Boot-managed |
| JSON Schema | com.networknt:json-schema-validator | 1.5.9 |
| Regex | com.google.re2j:re2j | 1.8 |
| IDs | com.github.f4b6a3:uuid-creator | 6.1.1 |
| Claude | com.anthropic:anthropic-java | 2.75.0 (latest 2.x; 3.x exists but the PRD baseline is 2.x) |
| MCP | hand-written JSON-RPC subset (see ADR-008) | — |
| JWT | com.nimbusds:nimbus-jose-jwt | 10.10 |
| Rate limit | com.bucket4j:bucket4j_jdk17-core | 8.21.0 |
| Logs | net.logstash.logback:logstash-logback-encoder | 8.1 |
| Tests | jqwik / ArchUnit / JMH / Testcontainers | 1.9.3 / 1.4.2 / 1.37 / Boot-managed |
| Format | Spotless maven plugin / google-java-format | 3.10.4 / 1.28.0 |
| Coverage | JaCoCo | 0.8.15 |
| Console | React / React Router / TanStack Query / Tailwind / Vite / TypeScript | 18.3.1 / 6.30.6 / 5.104.5 / 4.3.3 / 8.3.5 / 5.9.3 |
| Console | Radix Dialog / DropdownMenu / Tabs / Tooltip / Toast | 1.3.1 / 2.2.1 / 1.2.1 / 1.4.1 / 1.3.1 |
| Console tests | Playwright / @axe-core/playwright / Vitest | 1.64.1 / 4.14.0 / 5.0.5 |
| Infra | postgres / nginx / node (build) | 17 / 1.29-alpine / 22-alpine |

**Alternatives.** google-java-format 1.37.0 (latest) fails inside Spotless 3.10.4 with an
`InvocationTargetException` on every file; 1.28.0 works. We pin 1.28.0.

**Consequences.** Upgrades are deliberate and recorded here.
