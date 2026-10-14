import React, { useState } from 'react'
import { User, UserCheck, Activity, Dog, ShieldAlert } from 'lucide-react'
import type { EventType, PerchEvent } from '../lib/api'
import { formatRelativeTime } from '../lib/format'
import type { Connection } from '../hooks/usePerchStatus'

interface EventFeedProps {
  events: PerchEvent[]
  connection: Connection
}

type Filter = 'all' | 'people' | 'motion'

const FILTERS: { id: Filter; label: string }[] = [
  { id: 'all', label: 'All' },
  { id: 'people', label: 'People' },
  { id: 'motion', label: 'Motion' }
]

const FILTER_TYPES: Record<Exclude<Filter, 'all'>, EventType[]> = {
  people: ['FACE_STRANGER', 'FACE_RECOGNIZED'],
  motion: ['MOTION']
}

const EVENT_STYLES: Record<EventType, { icon: React.ReactNode; classes: string }> = {
  FACE_STRANGER: { icon: <User className="w-4 h-4" />, classes: 'bg-[#00BCD4]/15 text-[#00BCD4]' },
  FACE_RECOGNIZED: { icon: <UserCheck className="w-4 h-4" />, classes: 'bg-[#00E676]/15 text-[#00E676]' },
  MOTION: { icon: <Activity className="w-4 h-4" />, classes: 'bg-[#FF9100]/15 text-[#FF9100]' },
  PET: { icon: <Dog className="w-4 h-4" />, classes: 'bg-[#2979FF]/15 text-[#2979FF]' },
  TAMPER: { icon: <ShieldAlert className="w-4 h-4" />, classes: 'bg-[#FF1744]/15 text-[#FF1744]' }
}

const FALLBACK_STYLE = EVENT_STYLES.MOTION

export function filterEvents(events: PerchEvent[], filter: Filter): PerchEvent[] {
  if (filter === 'all') return events
  const types = FILTER_TYPES[filter]
  return events.filter(event => types.includes(event.type))
}

export const EventFeed: React.FC<EventFeedProps> = ({ events, connection }) => {
  const [filter, setFilter] = useState<Filter>('all')
  const visible = filterEvents(events, filter)
  const now = Date.now()

  return (
    <div className="bg-[#141F2B] border border-[#1A2634] rounded-2xl p-5 flex flex-col h-full shadow-xl">
      <div className="flex items-center justify-between mb-4">
        <div>
          <h2 className="text-base font-bold text-white tracking-wide">Recent Events</h2>
          <p className="text-xs text-[#8899AA]">Detections reported by the phone</p>
        </div>
        {connection === 'online' && (
          <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-[#00E676]/10 text-[#00E676] border border-[#00E676]/30">
            Live
          </span>
        )}
        {(connection === 'offline' || connection === 'unauthorized' || connection === 'rate_limited') && (
          <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-[#8899AA]/10 text-[#8899AA] border border-[#8899AA]/30">
            Not updating
          </span>
        )}
      </div>

      {/* Filter Tabs */}
      <div className="flex items-center gap-1.5 p-1 bg-[#0F1923] rounded-xl mb-4 text-xs font-semibold">
        {FILTERS.map(tab => (
          <button
            key={tab.id}
            onClick={() => setFilter(tab.id)}
            aria-pressed={filter === tab.id}
            className={`flex-1 py-1.5 rounded-lg transition ${
              filter === tab.id ? 'bg-[#2979FF] text-white shadow-sm' : 'text-[#8899AA] hover:text-white'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Events List */}
      <div className="space-y-2.5 overflow-y-auto pr-1 flex-1 max-h-[380px]">
        {visible.length === 0 && (
          <p className="text-sm text-[#8899AA] text-center py-8">
            {events.length === 0 ? 'No events yet' : 'No events match this filter'}
          </p>
        )}
        {visible.map(event => {
          const style = EVENT_STYLES[event.type] ?? FALLBACK_STYLE
          return (
            <div
              key={event.id}
              className="p-3 bg-[#1A2634] border border-[#243447]/60 rounded-xl flex items-start gap-3"
            >
              <div className={`w-9 h-9 rounded-xl flex items-center justify-center shrink-0 ${style.classes}`}>
                {style.icon}
              </div>

              <div className="flex-1 min-w-0">
                <div className="flex items-center justify-between gap-2">
                  <p className="text-sm font-semibold text-white truncate">{event.title}</p>
                  <span className="text-[11px] font-mono text-[#8899AA] shrink-0">
                    {formatRelativeTime(event.timestamp, now)}
                  </span>
                </div>
                <p className="text-xs text-[#8899AA] mt-0.5 truncate">{event.description}</p>
                {event.confidence > 0 && (
                  <div className="flex items-center gap-2 mt-2">
                    <span className="text-[10px] font-mono font-medium px-1.5 py-0.5 rounded bg-black/40 text-white/80">
                      Confidence: {Math.round(event.confidence * 100)}%
                    </span>
                  </div>
                )}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
