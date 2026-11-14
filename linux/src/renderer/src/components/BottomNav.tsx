import { MessageSquare, Radio, RadioTower } from 'lucide-react'

export type Tab = 'chats' | 'broadcast' | 'walkie'

export function BottomNav({
  tab,
  onChange,
  unread,
  onlinePeers
}: {
  tab: Tab
  onChange: (tab: Tab) => void
  unread: number
  onlinePeers: number
}): React.JSX.Element {
  const items: Array<{ id: Tab; label: string; icon: React.JSX.Element; badge?: number }> = [
    { id: 'chats', label: 'Chats & Calls', icon: <MessageSquare size={21} />, badge: unread },
    { id: 'broadcast', label: 'Broadcast Hub', icon: <RadioTower size={21} /> },
    { id: 'walkie', label: 'Walkie-Talkie', icon: <Radio size={21} />, badge: onlinePeers }
  ]
  return (
    <nav className="bottom-nav" aria-label="Main">
      {items.map((item) => (
        <button
          key={item.id}
          type="button"
          className={`nav-item${tab === item.id ? ' active' : ''}`}
          onClick={() => onChange(item.id)}
          aria-current={tab === item.id ? 'page' : undefined}
        >
          <span className="nav-icon">
            {item.icon}
            {item.badge !== undefined && item.badge > 0 && (
              <span className={`nav-badge${item.id === 'walkie' ? ' soft' : ''}`}>
                {item.badge > 99 ? '99+' : item.badge}
              </span>
            )}
          </span>
          <span>{item.label}</span>
        </button>
      ))}
    </nav>
  )
}
