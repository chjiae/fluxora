# Fluxora

Fluxora is a multi-module Java foundation for a platform control plane and a data gateway.

## Modules

- `fluxora-common`: shared, service-independent types.
- `fluxora-platform`: Spring Boot control plane.
- `fluxora-gateway`: Vert.x data gateway.

## Prerequisites

- JDK 25
- Maven 3.9+

## Verify the foundation

```powershell
mvn validate
```

This initial commit only establishes the Maven reactor and central dependency management. It deliberately contains no business implementation or runnable service.
