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
- Update `@JsonCreator`: insert `@JsonProperty("correlationId") String correlationId` after the
  `sagaId` parameter and before the `source` parameter; switch `super()` call to the 9-arg form:
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

- `correlationId` is already in scope in `publishSuccess()`; add it as a new argument in the
  `notificationEventPublisher.publishEbmsSentNotification(...)` call inside `publishSuccess()`.
  The call site must be updated to pass `correlationId` as the second argument (after `sagaId`).

**`AsyncNotificationEventPublisher.java`**

- No production code changes required; `AsyncNotificationEventPublisher` is a decorator/async
  wrapper and does not call `EbmsSentNotificationEvent.create()` directly.

**Tests** (`EbmsSentNotificationEventTest`, `NotificationEventPublisherTest`,
`AsyncNotificationEventPublisherTest`, `EbmsSendingEventPublisherTest`)

- Add a meaningful `correlationId` to every `create()` / `publishEbmsSentNotification()` call
  site. Derive from existing test context where possible (e.g. `"corr-" + documentId`, or reuse
  a declared constant). Use a descriptive literal where no context is available.
- Update all `verify(notificationEventPublisher).publishEbmsSentNotification(...)` calls in
  `EbmsSendingEventPublisherTest` to include the new `correlationId` argument.
- Also update the three `verify(notificationEventPublisher, never()).publishEbmsSentNotification(
  any(), any(), any(), any(), any(), any(), any())` calls (currently 7 `any()` matchers) to add
  an eighth `any()` matcher for the new `correlationId` parameter.
- `AsyncNotificationEventPublisherTest` has **6** `EbmsSentNotificationEvent.create()` call
  sites — each one must receive the new `correlationId` argument. (The production
  `AsyncNotificationEventPublisher` class requires no changes.)
- `EbmsSentNotificationEventTest.shouldHaveJsonCreatorConstructor()` uses Java reflection with an
  explicit parameter-type array. After adding `correlationId` to the `@JsonCreator`, insert
  `String.class` at position 5 (zero-indexed, between `String.class` for `sagaId` at position 4
  and `String.class` for `source` at the original position 5). The resulting array must have
  **15 elements**: `UUID.class, Instant.class, String.class, int.class, String.class, String.class,
  String.class, String.class, String.class, String.class, String.class, String.class, String.class,
  String.class, Instant.class` (the last `Instant.class` is `sentAt`; there is no `Long.class`).
- `EbmsSentNotificationEventTest.shouldHavePrivateConstructor()` also uses `getDeclaredConstructor`.
  The private creation constructor currently has 7 params `(String sagaId, String documentId,
  String invoiceId, String invoiceNumber, String documentType, String ebmsMessageId, Instant sentAt)`.
  After adding `String correlationId` as the second parameter (after `sagaId`), the array must have
  **8 elements**: `String.class, String.class, String.class, String.class, String.class, String.class,
  String.class, Instant.class`. Insert the new `String.class` at index 1.

---

### Service 3 — invoice-pdf-generation-service

**`InvoicePdfGeneratedEvent.java`**

- Remove `private final String correlationId` field and its `@JsonProperty("correlationId")`
  annotation.
- Change creation constructor `super(null, SOURCE, TRACE_TYPE, null)` →
  `super(null, correlationId, SOURCE, TRACE_TYPE, null)`. External constructor signature is
  unchanged — `correlationId` is still the last parameter.
- Update `@JsonCreator`: the `correlationId` parameter **already exists** in the `@JsonCreator`
  parameter list (it was wired to the now-removed subclass field). Do NOT add a duplicate parameter.
  Remove the `this.correlationId = correlationId;` assignment from the `@JsonCreator` body.
  Change the `super()` call body to the 9-arg form:
  `super(eventId, occurredAt, eventType, version, null, correlationId, source, traceType, context)`.
  Pass `null` for `sagaId` intentionally — this is a notification event, not saga-scoped. The
  `sagaId` `@JsonCreator` parameter stays in the parameter list for JSON compatibility but is
  discarded.

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
  `super(invoiceId, correlationId, SOURCE, TRACE_TYPE, null)`. Also remove the
  `this.correlationId = correlationId;` assignment from the creation constructor body.
  External constructor signature unchanged — `correlationId` is still the last parameter.
- Update `@JsonCreator`: the `correlationId` parameter **already exists** in the `@JsonCreator`
  parameter list (it was wired to the now-removed subclass field). Do NOT add a duplicate parameter.
  Remove the `this.correlationId = correlationId;` assignment from the `@JsonCreator` body.
  Change the `super()` call body to the 9-arg form:
  `super(eventId, occurredAt, eventType, version, sagaId, correlationId, source, traceType, context)`.

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
