import axios from 'axios'
import type {
  ScanRequest,
  ScanResponse,
  QueryRequest,
  QueryResponse,
  PreCannedQuery,
  StatsResponse,
  HealthResponse,
  FileContentResponse,
  TransformRequest,
  TransformResponse,
  FileComparisonResponse
} from '../types'

/*
 * API client for the Constants Catalog backend.
 *
 * All endpoints are prefixed with /api and proxy through Vite in development.
 * In production, the frontend is served from the same origin as the backend.
 */
const api = axios.create({
  baseURL: '/api',
  headers: {
    'Content-Type': 'application/json',
  },
})

/*
 * Scan a project directory and populate the graph database.
 */
export async function scanProject(request: ScanRequest): Promise<ScanResponse> {
  const response = await api.post<ScanResponse>('/scan', request)
  return response.data
}

/*
 * Get the list of pre-canned queries.
 */
export async function getPreCannedQueries(): Promise<PreCannedQuery[]> {
  const response = await api.get<PreCannedQuery[]>('/query/precanned')
  return response.data
}

/*
 * Execute a pre-canned query by its ID.
 */
export async function executePreCannedQuery(queryId: string): Promise<QueryResponse> {
  const response = await api.get<QueryResponse>(`/query/precanned/${queryId}`)
  return response.data
}

/*
 * Execute an ad-hoc Cypher query.
 */
export async function executeQuery(request: QueryRequest): Promise<QueryResponse> {
  const response = await api.post<QueryResponse>('/query/execute', request)
  return response.data
}

/*
 * Get database statistics for the dashboard.
 */
export async function getStats(): Promise<StatsResponse> {
  const response = await api.get<StatsResponse>('/stats')
  return response.data
}

/*
 * Health check for connection status display.
 */
export async function healthCheck(): Promise<HealthResponse> {
  const response = await api.get<HealthResponse>('/stats/health')
  return response.data
}

/*
 * Get file contents by container path.
 */
export async function getFileContent(path: string): Promise<FileContentResponse> {
  const response = await api.get<FileContentResponse>('/files/content', {
    params: { path },
  })
  return response.data
}

/*
 * Get the URL for the scan progress SSE stream.
 */
export function getScanStreamUrl(projectPath: string): string {
  return `/api/scan/stream?projectPath=${encodeURIComponent(projectPath)}`
}

/*
 * Transform constants in a project to config lookups.
 */
export async function transformProject(request: TransformRequest): Promise<TransformResponse> {
  const response = await api.post<TransformResponse>('/transform', request)
  return response.data
}

export interface FileComparisonRequest {
  filePath: string
  projectPath?: string
  branch?: string
  backupFilePath?: string
  dryRun?: boolean
}

/*
 * Get before/after comparison for a transformed file. The request shape
 * tells the backend which dispatch branch to take:
 *   - dryRun=true (with projectPath) -> backend synthesizes via previewFile()
 *   - branch set                     -> backend reads from the transform branch
 *   - backupFilePath set             -> backend reads from the .orig file
 *   - none                           -> backend returns an informative message
 */
export async function getFileComparison(req: FileComparisonRequest): Promise<FileComparisonResponse> {
  const response = await api.get<FileComparisonResponse>('/transform/compare', {
    params: {
      path: req.filePath,
      projectPath: req.projectPath,
      branch: req.branch,
      backupFilePath: req.backupFilePath,
      dryRun: req.dryRun,
    },
  })
  return response.data
}

export default api
