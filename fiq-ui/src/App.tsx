import { Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './AppShell'
import { AuditSettingsPage, ConnectionsPage, OperationsPage, OverviewPage, PoliciesPage, TableDetailPage, TablesPage } from './pages'

export function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<OverviewPage />} />
        <Route path="tables" element={<TablesPage />} />
        <Route path="tables/:tableId" element={<TableDetailPage />} />
        <Route path="policies" element={<PoliciesPage />} />
        <Route path="operations" element={<OperationsPage />} />
        <Route path="connections" element={<ConnectionsPage />} />
        <Route path="audit" element={<AuditSettingsPage />} />
        <Route path="*" element={<Navigate replace to="/" />} />
      </Route>
    </Routes>
  )
}
