import { useState, useEffect } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { FolderSearch, Play, CheckCircle, XCircle, Loader2, Radio } from 'lucide-react'
import { scanProject } from '../services/api'
import { useScanProgress } from '../hooks/useScanProgress'
import type { ScanResponse } from '../types'

/*
 * Project scanning interface with real-time SSE progress.
 *
 * Allows users to specify a project path and trigger a scan.
 * Shows live progress via Server-Sent Events with a scrolling file list.
 */
export default function ScanPage() {
  const queryClient = useQueryClient()
  const [projectPath, setProjectPath] = useState('')
  const [scanResult, setScanResult] = useState<ScanResponse | null>(null)
  const [watchProgress, setWatchProgress] = useState(true)

  const { isConnected, isComplete, latestFiles, currentEvent, startStreaming } =
    useScanProgress()

  // When SSE scan completes, build a result from the completion event
  useEffect(() => {
    if (isComplete && currentEvent) {
      setScanResult({
        success: true,
        message: 'Scan completed successfully',
        constantsFound: currentEvent.constantsFound,
        parametersFound: currentEvent.parametersFound,
        javaFilesScanned: currentEvent.totalFiles,
      })
      queryClient.invalidateQueries({ queryKey: ['stats'] })
    }
  }, [isComplete, currentEvent, queryClient])

  const scanMutation = useMutation({
    mutationFn: scanProject,
    onSuccess: (data) => {
      setScanResult(data)
      queryClient.invalidateQueries({ queryKey: ['stats'] })
    },
    onError: (error) => {
      setScanResult({
        success: false,
        message: error instanceof Error ? error.message : 'Unknown error',
      })
    },
  })

  const handleScan = (e: React.FormEvent) => {
    e.preventDefault()
    if (!projectPath.trim()) return
    setScanResult(null)

    if (watchProgress) {
      // Use the SSE endpoint only — it runs its own scan with progress events.
      // Do NOT also fire the POST mutation, as two concurrent scans on the same
      // project cause a ConcurrentModificationException in the backend.
      startStreaming(projectPath.trim())
    } else {
      // No progress watching — use the POST endpoint for the final result.
      scanMutation.mutate({ projectPath: projectPath.trim() })
    }
  }

  const progressPercent = currentEvent?.totalFiles
    ? Math.round((currentEvent.fileIndex / currentEvent.totalFiles) * 100)
    : 0

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div>
        <h1 className="text-2xl font-bold text-neon-cyan">Project Scanner</h1>
        <p className="text-terminal-dim mt-1">
          Analyze Java projects to catalog constants and parameters
        </p>
      </div>

      {/* Scan Form */}
      <div className="bg-space-darker border border-space-light rounded-lg p-6">
        <form onSubmit={handleScan} className="space-y-4">
          <div>
            <label className="block text-terminal-text mb-2">
              Project Path
            </label>
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
                disabled={scanMutation.isPending || isConnected || !projectPath.trim()}
                className="btn btn-primary flex items-center gap-2"
              >
                {(scanMutation.isPending || isConnected) ? (
                  <>
                    <Loader2 className="w-4 h-4 animate-spin" />
                    Scanning...
                  </>
                ) : (
                  <>
                    <Play className="w-4 h-4" />
                    Scan Project
                  </>
                )}
              </button>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <input
              type="checkbox"
              id="watchProgress"
              checked={watchProgress}
              onChange={(e) => setWatchProgress(e.target.checked)}
              className="rounded border-space-light"
            />
            <label htmlFor="watchProgress" className="text-terminal-dim text-sm flex items-center gap-1">
              <Radio className="w-3 h-3" />
              Watch scan progress (live file updates)
            </label>
          </div>

          <div className="text-terminal-dim text-sm">
            Enter the absolute path to a Java project directory. The scanner will analyze:
            <ul className="list-disc list-inside mt-2 space-y-1">
              <li>Java files for public static final constants and enum values</li>
              <li>Property files (.properties, .yml, .yaml, .env, .json, .xml)</li>
              <li>Parameter usages in Java code</li>
            </ul>
          </div>
        </form>
      </div>

      {/* Live Scan Progress */}
      {(scanMutation.isPending || isConnected) && (
        <div className="bg-space-darker border border-neon-cyan/30 rounded-lg p-6">
          <div className="flex items-center gap-3 mb-4">
            <Loader2 className="w-6 h-6 text-neon-cyan animate-spin" />
            <div className="flex-1">
              <div className="text-neon-cyan font-medium">Scanning in progress...</div>
              {currentEvent && (
                <div className="text-terminal-dim text-sm mt-1">
                  File {currentEvent.fileIndex} of {currentEvent.totalFiles}
                  {' | '}
                  {currentEvent.constantsFound} constants, {currentEvent.parametersFound} parameters found
                </div>
              )}
            </div>
            {isConnected && (
              <div className="flex items-center gap-1 text-neon-green text-xs">
                <div className="w-2 h-2 rounded-full bg-neon-green animate-pulse" />
                LIVE
              </div>
            )}
          </div>

          {/* Progress Bar */}
          {currentEvent?.totalFiles && currentEvent.totalFiles > 0 && (
            <div className="mb-4">
              <div className="h-2 bg-space-light rounded-full overflow-hidden">
                <div
                  className="h-full bg-neon-cyan transition-all duration-300"
                  style={{ width: `${progressPercent}%` }}
                />
              </div>
              <div className="text-terminal-dim text-xs mt-1 text-right">{progressPercent}%</div>
            </div>
          )}

          {/* Scrolling File List */}
          {latestFiles.length > 0 && (
            <div className="mt-4">
              <div className="text-terminal-dim text-xs mb-2">Recent files processed:</div>
              <div className="bg-space-medium rounded p-3 max-h-48 overflow-y-auto font-mono text-xs">
                {latestFiles.map((file, i) => (
                  <div
                    key={i}
                    className={`py-0.5 ${
                      i === latestFiles.length - 1
                        ? 'text-neon-cyan'
                        : 'text-terminal-dim'
                    }`}
                  >
                    {i === latestFiles.length - 1 ? '> ' : '  '}
                    {file}
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {/* Scan Results */}
      {scanResult && (
        <div
          className={`bg-space-darker border rounded-lg p-6 ${
            scanResult.success
              ? 'border-neon-green/30'
              : 'border-neon-red/30'
          }`}
        >
          <div className="flex items-start gap-3">
            {scanResult.success ? (
              <CheckCircle className="w-6 h-6 text-neon-green flex-shrink-0" />
            ) : (
              <XCircle className="w-6 h-6 text-neon-red flex-shrink-0" />
            )}

            <div className="flex-1">
              <div
                className={`font-medium ${
                  scanResult.success ? 'text-neon-green' : 'text-neon-red'
                }`}
              >
                {scanResult.success ? 'Scan Completed Successfully' : 'Scan Failed'}
              </div>
              <div className="text-terminal-dim text-sm mt-1">
                {scanResult.message}
              </div>

              {scanResult.success && (
                <>
                  <div className="mt-4 grid grid-cols-5 gap-4">
                    <ResultStat label="Project" value={scanResult.projectName || '-'} />
                    <ResultStat
                      label="Java Files"
                      value={scanResult.javaFilesScanned?.toString() || '0'}
                    />
                    <ResultStat
                      label="Property Files"
                      value={scanResult.propertyFilesScanned?.toString() || '0'}
                    />
                    <ResultStat
                      label="Constants"
                      value={scanResult.constantsFound?.toString() || '0'}
                      highlight
                    />
                    <ResultStat
                      label="Parameters"
                      value={scanResult.parametersFound?.toString() || '0'}
                      highlight
                    />
                  </div>

                  {scanResult.fileTypeStats && (
                    <div className="mt-4 border-t border-space-light pt-4">
                      <div className="text-terminal-dim text-xs mb-2 uppercase tracking-wide">File Type Breakdown</div>
                      <div className="grid grid-cols-6 gap-4">
                        <ResultStat label=".properties" value={scanResult.fileTypeStats.propertiesFiles.toString()} />
                        <ResultStat label=".yml/.yaml" value={scanResult.fileTypeStats.ymlFiles.toString()} />
                        <ResultStat label=".xml" value={scanResult.fileTypeStats.xmlFiles.toString()} />
                        <ResultStat label=".json" value={scanResult.fileTypeStats.jsonFiles.toString()} />
                        <ResultStat label=".env" value={scanResult.fileTypeStats.envFiles.toString()} />
                        <ResultStat
                          label="public static final"
                          value={scanResult.fileTypeStats.publicStaticFinals.toString()}
                          highlight
                        />
                      </div>
                    </div>
                  )}
                </>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Help Section */}
      <div className="bg-space-medium border border-space-light rounded-lg p-4">
        <h3 className="text-terminal-text font-medium mb-2">Tips</h3>
        <ul className="text-terminal-dim text-sm space-y-1">
          <li>
            The scanner skips common build directories (target, build, node_modules)
          </li>
          <li>
            Re-scanning a project updates existing data rather than duplicating
          </li>
          <li>
            After scanning, use the Query page to explore the results
          </li>
          <li>
            Enable "Watch scan progress" to see files being processed in real-time
          </li>
        </ul>
      </div>
    </div>
  )
}

function ResultStat({
  label,
  value,
  highlight = false,
}: {
  label: string
  value: string
  highlight?: boolean
}) {
  return (
    <div className="text-center">
      <div className={`text-2xl font-bold ${highlight ? 'text-neon-cyan' : 'text-terminal-text'}`}>
        {value}
      </div>
      <div className="text-terminal-dim text-sm">{label}</div>
    </div>
  )
}
