import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import {
  FolderSearch,
  Play,
  CheckCircle,
  XCircle,
  Loader2,
  Eye,
  SkipForward,
  AlertTriangle,
  GitBranch,
} from 'lucide-react'
import axios from 'axios'
import { transformProject, getFileComparison } from '../services/api'
import DiffViewer from '../components/DiffViewer'
import type { TransformResponse, TransformEntry, FileComparisonResponse } from '../types'

type CandidateScope = 'ALL' | 'CONSTANTS_ONLY' | 'PARAMETERS_ONLY' | 'REMOVE_UNUSED'
type StatusFilter = 'ALL' | 'TRANSFORMED' | 'SKIPPED' | 'FAILED'

/*
 * Transformation page for replacing constant usages and parameter getter calls
 * with config.lookupType() calls. Supports dry-run mode and shows a before/after
 * comparison viewer.
 */
export default function TransformPage() {
  const [projectPath, setProjectPath] = useState('')
  const [fileFilter, setFileFilter] = useState('')
  const [dryRun, setDryRun] = useState(true)
  const [createBranch, setCreateBranch] = useState(false)
  const [candidateScope, setCandidateScope] = useState<CandidateScope>('ALL')
  const [result, setResult] = useState<TransformResponse | null>(null)
  const [comparison, setComparison] = useState<FileComparisonResponse | null>(null)
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL')

  const transformMutation = useMutation({
    mutationFn: transformProject,
    onSuccess: (data) => setResult(data),
    onError: (error) => {
      // Extract the actual error message from the Axios response body if available,
      // otherwise fall back to the generic Axios/Error message.
      let message = 'Unknown error'
      if (axios.isAxiosError(error) && error.response?.data?.message) {
        message = error.response.data.message
      } else if (error instanceof Error) {
        message = error.message
      }

      setResult({
        success: false,
        message,
        projectPath,
        transformedAt: new Date().toISOString(),
        dryRun,
        totalTransformed: 0,
        totalSkipped: 0,
        totalFailed: 0,
        entries: [],
      })
    },
  })

  const compareMutation = useMutation({
    mutationFn: getFileComparison,
    onSuccess: (data) => setComparison(data),
  })

  const handleTransform = (e: React.FormEvent) => {
    e.preventDefault()
    if (!projectPath.trim()) return
    setResult(null)
    setComparison(null)
    setStatusFilter('ALL')
    transformMutation.mutate({
      projectPath: projectPath.trim(),
      dryRun,
      candidateScope,
      createBranch: !dryRun && createBranch,
      fileFilter: fileFilter.trim() || undefined,
    })
  }

  const handleViewComparison = (entry: TransformEntry) => {
    compareMutation.mutate({
      filePath: entry.filePath,
      projectPath: result?.projectPath,
      branch: result?.branchName ?? undefined,
      backupFilePath: entry.backupFilePath ?? undefined,
      dryRun: result?.dryRun ?? false,
    })
  }

  const statusIcon = (status: string) => {
    switch (status) {
      case 'TRANSFORMED':
        return <CheckCircle className="w-4 h-4 text-neon-green" />
      case 'SKIPPED':
        return <SkipForward className="w-4 h-4 text-yellow-400" />
      case 'FAILED':
        return <AlertTriangle className="w-4 h-4 text-neon-red" />
      default:
        return null
    }
  }

  const sourceBadge = (sourceKind: string) => {
    if (sourceKind === 'PARAMETER') {
      return (
        <span className="inline-flex items-center px-1.5 py-0.5 rounded text-xs font-medium bg-blue-500/20 text-blue-400 border border-blue-500/30">
          Param
        </span>
      )
    }
    return (
      <span className="inline-flex items-center px-1.5 py-0.5 rounded text-xs font-medium bg-purple-500/20 text-purple-400 border border-purple-500/30">
        Const
      </span>
    )
  }

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div>
        <h1 className="text-2xl font-bold text-neon-cyan">Code Transformer</h1>
        <p className="text-terminal-dim mt-1">
          Replace constant usages and parameter lookups with config.lookupType() calls
        </p>
      </div>

      {/* Transform Form */}
      <div className="bg-space-darker border border-space-light rounded-lg p-6">
        <form onSubmit={handleTransform} className="space-y-4">
          <div>
            <label className="block text-terminal-text mb-2">Project Path</label>
            <div className="flex gap-2">
              <div className="flex-1 relative">
                <FolderSearch className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-terminal-dim" />
                <input
                  type="text"
                  value={projectPath}
                  onChange={(e) => setProjectPath(e.target.value)}
                  placeholder="/path/to/java/project"
                  className="w-full pl-10"
                />
              </div>
              <button
                type="submit"
                disabled={transformMutation.isPending || !projectPath.trim()}
                className="btn btn-primary flex items-center gap-2"
              >
                {transformMutation.isPending ? (
                  <>
                    <Loader2 className="w-4 h-4 animate-spin" />
                    Transforming...
                  </>
                ) : (
                  <>
                    <Play className="w-4 h-4" />
                    {dryRun ? 'Dry Run' : 'Transform'}
                  </>
                )}
              </button>
            </div>
          </div>

          {/* File Filter */}
          <div>
            <label className="block text-terminal-text mb-2 text-sm">File Filter (optional)</label>
            <input
              type="text"
              value={fileFilter}
              onChange={(e) => setFileFilter(e.target.value)}
              placeholder="e.g., src/main/java or AppConfig.java"
              className="w-full"
            />
            <div className="text-terminal-dim text-xs mt-1">
              Only files whose path contains this text will be transformed. Leave empty to transform all files.
            </div>
          </div>

          {/* Scope Selector */}
          <div>
            <label className="block text-terminal-text mb-2 text-sm">Candidate Scope</label>
            <div className="flex gap-4">
              {([
                ['ALL', 'All'],
                ['CONSTANTS_ONLY', 'Constants Only'],
                ['PARAMETERS_ONLY', 'Parameters Only'],
                ['REMOVE_UNUSED', 'Remove Unused'],
              ] as const).map(([value, label]) => (
                <label key={value} className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="radio"
                    name="candidateScope"
                    value={value}
                    checked={candidateScope === value}
                    onChange={() => setCandidateScope(value)}
                    className="text-neon-cyan"
                  />
                  <span className="text-terminal-dim text-sm">{label}</span>
                </label>
              ))}
            </div>
          </div>

          <div className="flex items-center gap-2">
            <input
              type="checkbox"
              id="dryRun"
              checked={dryRun}
              onChange={(e) => {
                setDryRun(e.target.checked)
                if (e.target.checked) setCreateBranch(false)
              }}
              className="rounded border-space-light"
            />
            <label htmlFor="dryRun" className="text-terminal-dim text-sm">
              Dry run (preview changes without modifying files)
            </label>
          </div>

          <div className="flex items-center gap-2">
            <input
              type="checkbox"
              id="createBranch"
              checked={createBranch}
              disabled={dryRun}
              onChange={(e) => {
                setCreateBranch(e.target.checked)
                if (e.target.checked) setDryRun(false)
              }}
              className="rounded border-space-light"
            />
            <GitBranch className="w-4 h-4 text-terminal-dim" />
            <label htmlFor="createBranch" className={`text-sm ${dryRun ? 'text-terminal-dim/50' : 'text-terminal-dim'}`}>
              Create new branch with transformed files committed
            </label>
          </div>

          <div className="text-terminal-dim text-sm">
            {candidateScope === 'REMOVE_UNUSED' ? (
              <>
                The remover will:
                <ul className="list-disc list-inside mt-2 space-y-1">
                  <li>Query the graph for constants with no usages in any scanned file</li>
                  <li>Remove their <code>static final</code> field declarations from source</li>
                  <li>Protect special names (serialVersionUID, LOG, LOGGER, etc.)</li>
                  <li>Backup original files as *.java.orig</li>
                </ul>
              </>
            ) : (
              <>
                The transformer will:
                <ul className="list-disc list-inside mt-2 space-y-1">
                  <li>Find constant usages and parameter lookups (getProperty, getInt, etc.)</li>
                  <li>Replace usages with config.lookup(), config.lookupInt(), etc.</li>
                  <li>Add SiteConfigurationService field with @Autowired</li>
                  <li>Backup original files as *.java.orig</li>
                </ul>
              </>
            )}
          </div>
        </form>
      </div>

      {/* Transform Results */}
      {result && (
        <div
          className={`bg-space-darker border rounded-lg p-6 ${
            result.success ? 'border-neon-green/30' : 'border-neon-red/30'
          }`}
        >
          <div className="flex items-start gap-3 mb-4">
            {result.success ? (
              <CheckCircle className="w-6 h-6 text-neon-green flex-shrink-0" />
            ) : (
              <XCircle className="w-6 h-6 text-neon-red flex-shrink-0" />
            )}
            <div>
              <div
                className={`font-medium ${
                  result.success ? 'text-neon-green' : 'text-neon-red'
                }`}
              >
                {result.dryRun ? 'Dry Run Complete' : 'Transformation Complete'}
              </div>
              <div className="text-terminal-dim text-sm mt-1">{result.message}</div>
              {result.branchName && (
                <div className="flex items-center gap-2 mt-2 text-sm text-neon-cyan">
                  <GitBranch className="w-4 h-4" />
                  <span className="font-mono">{result.branchName}</span>
                </div>
              )}
            </div>
          </div>

          {/* Summary Stats */}
          {result.success && (
            <div className="grid grid-cols-3 gap-4 mb-6">
              <div className="text-center bg-space-medium rounded p-3">
                <div className="text-2xl font-bold text-neon-green">{result.totalTransformed}</div>
                <div className="text-terminal-dim text-sm">Transformed</div>
              </div>
              <div className="text-center bg-space-medium rounded p-3">
                <div className="text-2xl font-bold text-yellow-400">{result.totalSkipped}</div>
                <div className="text-terminal-dim text-sm">Skipped</div>
              </div>
              <div className="text-center bg-space-medium rounded p-3">
                <div className="text-2xl font-bold text-neon-red">{result.totalFailed}</div>
                <div className="text-terminal-dim text-sm">Failed</div>
              </div>
            </div>
          )}

          {/* Status Filter Buttons */}
          {result.entries.length > 0 && (
            <div className="flex gap-2 mb-4">
              {([
                { value: 'ALL' as StatusFilter, label: 'All', count: result.entries.length, color: 'text-terminal-text border-terminal-text' },
                { value: 'TRANSFORMED' as StatusFilter, label: 'Transformed', count: result.totalTransformed, color: 'text-neon-green border-neon-green' },
                { value: 'SKIPPED' as StatusFilter, label: 'Skipped', count: result.totalSkipped, color: 'text-yellow-400 border-yellow-400' },
                { value: 'FAILED' as StatusFilter, label: 'Failed', count: result.totalFailed, color: 'text-neon-red border-neon-red' },
              ]).map(({ value, label, count, color }) => (
                <button
                  key={value}
                  onClick={() => setStatusFilter(value)}
                  className={`px-3 py-1.5 rounded border text-sm font-medium transition-colors ${
                    statusFilter === value
                      ? `${color} bg-space-medium`
                      : 'text-terminal-dim border-space-light hover:border-terminal-dim'
                  }`}
                >
                  {label} ({count})
                </button>
              ))}
            </div>
          )}

          {/* Entries Table */}
          {result.entries.length > 0 && (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-space-light text-terminal-dim">
                    <th className="text-left py-2 px-3">Status</th>
                    <th className="text-left py-2 px-3">Source</th>
                    <th className="text-left py-2 px-3">Name</th>
                    <th className="text-left py-2 px-3">Type</th>
                    <th className="text-left py-2 px-3">Property Key</th>
                    <th className="text-left py-2 px-3">File</th>
                    <th className="text-left py-2 px-3">Line</th>
                    <th className="text-left py-2 px-3">Message</th>
                    <th className="text-left py-2 px-3">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {result.entries
                    .filter((entry) => statusFilter === 'ALL' || entry.status === statusFilter)
                    .map((entry, i) => (
                    <tr
                      key={i}
                      className="border-b border-space-light/50 hover:bg-space-medium/50"
                    >
                      <td className="py-2 px-3">{statusIcon(entry.status)}</td>
                      <td className="py-2 px-3">{sourceBadge(entry.sourceKind)}</td>
                      <td className="py-2 px-3 text-terminal-text font-mono">
                        {entry.constantName}
                      </td>
                      <td className="py-2 px-3 text-terminal-dim">{entry.originalType}</td>
                      <td className="py-2 px-3 text-neon-cyan font-mono">{entry.propertyKey}</td>
                      <td className="py-2 px-3 text-terminal-dim text-xs truncate max-w-[200px]">
                        {entry.filePath.split('/').pop() || entry.filePath.split('\\').pop()}
                      </td>
                      <td className="py-2 px-3 text-terminal-dim">{entry.lineNumber}</td>
                      <td className="py-2 px-3 text-terminal-dim text-xs truncate max-w-[200px]">
                        {entry.message}
                      </td>
                      <td className="py-2 px-3">
                        <button
                          onClick={() => handleViewComparison(entry)}
                          className="text-neon-cyan hover:text-neon-cyan/80 flex items-center gap-1 text-xs"
                          disabled={compareMutation.isPending}
                        >
                          <Eye className="w-3 h-3" />
                          View
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {/* Diff Viewer Modal */}
      {comparison && (
        <DiffViewer
          originalContent={comparison.originalContent || ''}
          transformedContent={comparison.transformedContent || ''}
          filePath={comparison.filePath}
          onClose={() => setComparison(null)}
        />
      )}
    </div>
  )
}
