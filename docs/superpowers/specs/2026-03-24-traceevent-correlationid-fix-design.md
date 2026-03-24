# Design: Fix TraceEvent correlationId Propagation Across 4 Services

**Date**: 2026-03-24
**Status**: Approved

## Background

`TraceEvent` (saga-commons) gained a first-class `correlationId` field with backward-compatible
constructors. Four `TraceEvent` subclasses across four microservices were not updated and now have
one of two problems:

| Class | Service | Problem |
|---|---|---|
| `TaxInvoicePdfGeneratedEvent` | taxinvoice-pdf-generation-service | Workaround: `correlationId` stored in `sagaId` slot; `getCorrelationId()` overridden with `@JsonIgnore` to read `getSagaId()` |
| `EbmsSentNotificationEvent` | ebms-sending-service | Not updated at all: `correlationId` absent from factory, constructor, and `@JsonCreator`; `TraceEvent.getCorrelationId()` always returns `null` |
| `InvoicePdfGeneratedEvent` | invoice-pdf-generation-service | Duplicate subclass field shadows `TraceEvent.getCorrelationId()`; not passed to `super()` |
| `DocumentStoredEvent` | document-storage-service | Same duplicate-field pattern as `InvoicePdfGeneratedEvent` |

All four compile and run today (old constructors are retained in `TraceEvent`), but
`TraceEvent.getCorrelationId()` returns incorrect or `null` values for these classes — breaking
any code that accesses the field polymorphically.

## Approach

**Parallel agents in isolated git worktrees** — one agent per service, all running simultaneously.
The four services touch completely disjoint files; branches merge into `main` with no conflicts.

| Agent | Branch |
|---|---|
| 1 | `fix/taxinvoice-pdf-traceevent` |
| 2 | `fix/ebms-sending-traceevent` |
| 3 | `fix/invoice-pdf-traceevent` |
| 4 | `fix/document-storage-traceevent` |

## Per-Service Changes

### Service 1 — taxinvoice-pdf-generation-service

**`TaxInvoicePdfGeneratedEvent.java`**

- Add `String sagaId` as first parameter to the creation constructor (before the existing
  `correlationId` parameter). The caller (`TaxInvoicePdfDocumentService`) already has `sagaId`
  in scope via the saga command.
- Change `super(correlationId, SOURCE, TRACE_TYPE, null)` →
  `super(sagaId, correlationId, SOURCE, TRACE_TYPE, null)`.
- Remove the `@JsonIgnore getCorrelationId()` override — `TraceEvent.getCorrelationId()` now
  handles this correctly.
- Update `@JsonCreator`: add `@JsonProperty("correlationId") String correlationId` parameter;
  switch `super()` call to the 9-arg form:
  `super(eventId, occurredAt, eventType, version, sagaId, correlationId, source, traceType, context)`.

**`TaxInvoicePdfDocumentService.java` — `buildGeneratedEvent()`**

- Pass `command.getSagaId()` as the new first argument to the `TaxInvoicePdfGeneratedEvent`
  constructor.

**Tests** (`EventPublisherTest`, `CamelRouteConfigTest`)

- Add a meaningful `sagaId` argument to each `new TaxInvoicePdfGeneratedEvent(...)` call.
  Use a value consistent with existing test data (e.g. `"saga-001"` where the test already uses
  that saga ID).

---

### Service 2 — ebms-sending-service

**`EbmsSentNotificationEvent.java`**

- Add `String correlationId` to the `create()` factory signature and private constructor.
- Change `super(sagaId, SOURCE, TRACE_TYPE, null)` →
  `super(sagaId, correlationId, SOURCE, TRACE_TYPE, null)`.
- Update `@JsonCreator`: add `@JsonProperty("correlationId") String correlationId` parameter;
  switch `super()` to 9-arg form.

**`NotificationEventPublisher.java`**

- Add `String correlationId` parameter to `publishEbmsSentNotification()`.
- Pass it to `EbmsSentNotificationEvent.create(...)`.

**`EbmsSendingEventPublisher.java`**

- `correlationId` is already in scope in `publishSuccess()`; pass it to
  `notificationEventPublisher.publishEbmsSentNotification(...)`.

**Tests** (`EbmsSentNotificationEventTest`, `NotificationEventPublisherTest`,
`AsyncNotificationEventPublisherTest`, `EbmsSendingEventPublisherTest`)

- Add a meaningful `correlationId` to every `create()` / `publishEbmsSentNotification()` call
  site. Derive from existing test context where possible (e.g. `"corr-" + documentId`, or reuse
  a declared constant). Use a descriptive literal where no context is available.
- Update `verify(...).publishEbmsSentNotification(...)` calls to include the new parameter.

---

### Service 3 — invoice-pdf-generation-service

**`InvoicePdfGeneratedEvent.java`**

- Remove `private final String correlationId` field and its `@JsonProperty("correlationId")`
  annotation.
- Change creation constructor `super(null, SOURCE, TRACE_TYPE, null)` →
  `super(null, correlationId, SOURCE, TRACE_TYPE, null)`. External constructor signature is
  unchanged — `correlationId` is still the last parameter.
- Update `@JsonCreator`: add `@JsonProperty("correlationId") String correlationId` parameter;
  switch `super()` to 9-arg form.

**Callers** — `InvoicePdfDocumentService.buildGeneratedEvent()`: no change needed.

**Tests** (`EventPublisherTest`, `SagaRouteConfigTest`)

- No call-site changes required (constructor signature unchanged).
- Assertions on `event.getCorrelationId()` continue to pass — value source moves from the
  removed subclass field to `TraceEvent.getCorrelationId()`, but the value is identical.

---

### Service 4 — document-storage-service

**`DocumentStoredEvent.java`**

- Remove `private final String correlationId` field and its `@JsonProperty("correlationId")`
  annotation.
- Change creation constructor `super(invoiceId, SOURCE, TRACE_TYPE)` →
  `super(invoiceId, correlationId, SOURCE, TRACE_TYPE, null)`. External constructor signature
  unchanged — `correlationId` is still the last parameter.
- Update `@JsonCreator`: add `@JsonProperty("correlationId") String correlationId` parameter;
  switch `super()` to 9-arg form.

**Callers** — `SagaOrchestrationService`: no change needed.

**Tests** (`MessagePublisherAdapterTest`, `DocumentStoredEventTest`)

- No call-site changes required (constructor signature unchanged).
- Assertions on `getCorrelationId()` continue to pass.

---

## Backward Compatibility

- No compile errors in any service — `TraceEvent` retains all old constructors.
- Outbox events are short-lived (Debezium processes them near-instantly); no concern about
  in-flight events with the old JSON layout.
- The `@JsonCreator` changes are additive: `correlationId` is a new JSON property on outbound
  events and is tolerated as `null` for any in-flight events that lack it
  (`@JsonIgnoreProperties(ignoreUnknown=true)` is present on the intake-side consumers).

## Verification

After all agents complete, run `mvn test` in each affected service to confirm all tests pass.
No saga-commons rebuild is required.
