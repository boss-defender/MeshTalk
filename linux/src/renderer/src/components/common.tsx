import type { ReactNode } from 'react'
import { avatarColor, initials } from '../lib/format'

export function Avatar({
  name,
  color,
  size = 46,
  online,
  pulse = false
}: {
  name: string
  color?: number
  size?: number
  online?: boolean
  pulse?: boolean
}): React.JSX.Element {
  const tint = avatarColor(color)
  return (
    <div
      className={`avatar${pulse ? ' avatar-pulse' : ''}`}
      style={{
        width: size,
        height: size,
        fontSize: Math.max(11, Math.round(size * 0.32)),
        background: `linear-gradient(145deg, ${tint}38, ${tint}14)`,
        borderColor: `${tint}66`,
        color: tint
      }}
      aria-hidden="true"
    >
      {initials(name)}
      {online !== undefined && <span className={`avatar-presence ${online ? 'online' : 'offline'}`} />}
    </div>
  )
}

export function Waveform({
  levels,
  tone = 'cyan',
  active = false,
  height = 54
}: {
  levels: number[]
  tone?: 'cyan' | 'amber' | 'green' | 'red'
  active?: boolean
  height?: number
}): React.JSX.Element {
  return (
    <div className={`waveform waveform-${tone}${active ? ' active' : ''}`} style={{ height }} aria-hidden="true">
      {levels.map((level, index) => (
        <span
          key={index}
          style={{
            height: `${Math.max(4, Math.round(level * height))}px`,
            animationDelay: `${index * 45}ms`
          }}
        />
      ))}
    </div>
  )
}

export function Toggle({
  checked,
  onChange,
  disabled = false,
  label
}: {
  checked: boolean
  onChange: (next: boolean) => void
  disabled?: boolean
  label: string
}): React.JSX.Element {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      className={`switch${checked ? ' on' : ''}`}
      onClick={() => onChange(!checked)}
    >
      <span className="switch-knob" />
    </button>
  )
}

export function IconButton({
  children,
  label,
  onClick,
  active = false,
  className = '',
  disabled = false
}: {
  children: ReactNode
  label: string
  onClick?: () => void
  active?: boolean
  className?: string
  disabled?: boolean
}): React.JSX.Element {
  return (
    <button
      type="button"
      className={`icon-button${active ? ' active' : ''} ${className}`.trim()}
      aria-label={label}
      title={label}
      onClick={onClick}
      disabled={disabled}
    >
      {children}
    </button>
  )
}

export function SectionHeading({
  eyebrow,
  title,
  subtitle,
  right
}: {
  eyebrow?: string
  title: string
  subtitle?: string
  right?: ReactNode
}): React.JSX.Element {
  return (
    <div className="section-heading">
      <div>
        {eyebrow && <span className="eyebrow">{eyebrow}</span>}
        <h2>{title}</h2>
        {subtitle && <p>{subtitle}</p>}
      </div>
      {right && <div className="section-heading-right">{right}</div>}
    </div>
  )
}

export function EmptyState({
  icon,
  title,
  body
}: {
  icon: ReactNode
  title: string
  body: string
}): React.JSX.Element {
  return (
    <div className="empty-state">
      <div className="empty-icon">{icon}</div>
      <h3>{title}</h3>
      <p>{body}</p>
    </div>
  )
}

export function Divider(): React.JSX.Element {
  return <div className="divider" />
}
