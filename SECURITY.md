# Security policy

Report suspected vulnerabilities privately through GitHub Security Advisories. Do not include
credentials, production telemetry, robot identifiers, or exploit details in a public issue.

This reference application assumes authentication, device certificates, authorization, tenant
isolation, TLS termination, and rate limiting are enforced at the deployment perimeter. Its local
Compose topology is bound to loopback and has no external container network. Default credentials
are development-only and must be replaced through secret injection in any shared environment.
