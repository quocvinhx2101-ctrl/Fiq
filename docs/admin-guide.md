# Administration

Create workspaces and environments before exposing connections. Assign the smallest role:
`VIEWER` reads fleet state, `OPERATOR` assesses/plans/executes, `APPROVER` decides gated work, and
`ADMIN` manages connections and access. The backend checks every resource; hiding a UI control is
not an authorization boundary.

Policies select environment, catalog, namespace, table, and tags. Every enabled operation has its
own configuration. Retention below 168 hours, inventory vacuum, full optimize, and work above a
byte budget require approval. Z-order columns must be supplied by the user and liquid-clustered
tables reject Z-order. REORG auto-run must be explicitly enabled.

Use UTC in persistence. The UI presents the workspace timezone. Audit rows are protected by a
database trigger against updates and deletes. Database superusers can still bypass application
controls, so restrict that role and export audit data to immutable retention storage.
