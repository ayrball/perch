import { Component, type ErrorInfo, type ReactNode } from 'react'
import { AlertTriangle } from 'lucide-react'

interface ErrorBoundaryProps {
  children: ReactNode
}

interface ErrorBoundaryState {
  failed: boolean
}

/** Last line of defence: an unexpected render error shows a recovery screen instead of a blank page. */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { failed: false }

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { failed: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('Perch viewer crashed', error, info.componentStack)
  }

  render() {
    if (!this.state.failed) return this.props.children
    return (
      <div role="alert" className="min-h-screen bg-[#0F1923] text-white flex flex-col items-center justify-center gap-4 p-6 text-center">
        <AlertTriangle className="w-10 h-10 text-[#FF9100]" />
        <div>
          <h1 className="text-lg font-bold">Something went wrong</h1>
          <p className="text-sm text-[#8899AA] mt-1">The viewer hit an unexpected error. Your saved settings are untouched.</p>
        </div>
        <button
          onClick={() => window.location.reload()}
          className="px-4 py-2 rounded-lg bg-[#2979FF] hover:bg-[#5393FF] text-xs font-semibold text-white transition"
        >
          Reload viewer
        </button>
      </div>
    )
  }
}
