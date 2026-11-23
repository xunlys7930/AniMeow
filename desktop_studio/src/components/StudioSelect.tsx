import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'

export interface StudioSelectOption {
  value: string
  label: string
  sublabel?: string
  badges?: string[]
  disabled?: boolean
  title?: string
}

export interface StudioSelectProps {
  value: string
  options: StudioSelectOption[]
  onChange: (value: string) => void
  placeholder?: string
  triggerLabel?: string
  emptyText?: string
  disabled?: boolean
  className?: string
  menuClassName?: string
  ariaLabel?: string
  title?: string
  placement?: 'auto' | 'top' | 'bottom'
  zIndex?: number
  minMenuWidth?: number
  maxMenuWidth?: number
  footerAction?: {
    label: string
    onClick: () => void
  }
}

interface MenuPosition {
  left: number
  top: number
  width: number
  maxHeight: number
  openAbove: boolean
}

export const StudioSelect: React.FC<StudioSelectProps> = ({
  value,
  options,
  onChange,
  placeholder = '请选择',
  triggerLabel,
  emptyText = '暂无可选项',
  disabled = false,
  className = '',
  menuClassName = '',
  ariaLabel,
  title,
  placement = 'auto',
  zIndex = 1100,
  minMenuWidth = 220,
  maxMenuWidth = 340,
  footerAction
}) => {
  const [open, setOpen] = useState(false)
  const [highlightIndex, setHighlightIndex] = useState(-1)
  const [menuPos, setMenuPos] = useState<MenuPosition | null>(null)

  const triggerRef = useRef<HTMLButtonElement | null>(null)
  const menuRef = useRef<HTMLDivElement | null>(null)

  const selectedOption = useMemo(
    () => options.find((opt) => opt.value === value) ?? null,
    [options, value]
  )

  const computePosition = useCallback(() => {
    const trigger = triggerRef.current
    if (!trigger) return
    const rect = trigger.getBoundingClientRect()
    const viewportW = window.innerWidth
    const viewportH = window.innerHeight
    const gap = 6
    const edgeMargin = 10
    const spaceBelow = viewportH - rect.bottom - edgeMargin
    const spaceAbove = rect.top - edgeMargin

    const desiredHeight = Math.min(320, Math.max(120, options.length * 52 + (footerAction ? 48 : 16)))
    const openAbove =
      placement === 'top'
        ? spaceAbove >= Math.min(desiredHeight, 160) || spaceAbove >= spaceBelow
        : placement === 'bottom'
          ? false
          : spaceBelow < Math.min(desiredHeight, 200) && spaceAbove > spaceBelow

    const availableHeight = Math.max(120, Math.min(320, (openAbove ? spaceAbove : spaceBelow) - gap))
    const width = Math.min(maxMenuWidth, Math.max(minMenuWidth, rect.width))
    let left = rect.left
    if (left + width > viewportW - edgeMargin) {
      left = Math.max(edgeMargin, viewportW - edgeMargin - width)
    }
    if (left < edgeMargin) {
      left = edgeMargin
    }

    const top = openAbove ? Math.max(edgeMargin, rect.top - gap) : rect.bottom + gap
    setMenuPos({
      left,
      top,
      width,
      maxHeight: availableHeight,
      openAbove
    })
  }, [footerAction, maxMenuWidth, minMenuWidth, options.length, placement])

  useLayoutEffect(() => {
    if (!open) return
    computePosition()
  }, [open, computePosition])

  useEffect(() => {
    if (!open) return
    const selectedIdx = options.findIndex((o) => o.value === value && !o.disabled)
    if (selectedIdx >= 0) {
      setHighlightIndex(selectedIdx)
    } else {
      setHighlightIndex(options.findIndex((o) => !o.disabled))
    }
  }, [open, options, value])

  useEffect(() => {
    if (!open) return
    const handlePointerDown = (e: MouseEvent) => {
      const target = e.target as Node | null
      if (!target) return
      if (triggerRef.current?.contains(target) || menuRef.current?.contains(target)) {
        return
      }
      setOpen(false)
    }
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        setOpen(false)
        triggerRef.current?.focus()
      }
    }
    const handleReposition = () => computePosition()

    window.addEventListener('mousedown', handlePointerDown, true)
    window.addEventListener('keydown', handleKeyDown)
    window.addEventListener('resize', handleReposition)
    window.addEventListener('scroll', handleReposition, true)
    return () => {
      window.removeEventListener('mousedown', handlePointerDown, true)
      window.removeEventListener('keydown', handleKeyDown)
      window.removeEventListener('resize', handleReposition)
      window.removeEventListener('scroll', handleReposition, true)
    }
  }, [open, computePosition])

  const moveHighlight = (delta: number) => {
    if (options.length === 0) return
    let idx = highlightIndex
    for (let step = 0; step < options.length; step++) {
      idx = (idx + delta + options.length) % options.length
      if (!options[idx].disabled) {
        setHighlightIndex(idx)
        return
      }
    }
  }

  const handleSelect = (opt: StudioSelectOption) => {
    if (opt.disabled) return
    onChange(opt.value)
    setOpen(false)
    triggerRef.current?.focus()
  }

  const handleTriggerKeyDown = (e: React.KeyboardEvent<HTMLButtonElement>) => {
    if (disabled) return
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      if (!open) {
        setOpen(true)
      } else {
        moveHighlight(e.key === 'ArrowDown' ? 1 : -1)
      }
      return
    }
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      if (!open) {
        setOpen(true)
      } else if (highlightIndex >= 0 && options[highlightIndex] && !options[highlightIndex].disabled) {
        handleSelect(options[highlightIndex])
      } else {
        setOpen(false)
      }
    }
  }

  const displayLabel = triggerLabel ?? selectedOption?.label ?? placeholder

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        className={`studio-select-trigger ${open ? 'is-open' : ''} ${
          !selectedOption ? 'is-placeholder' : ''
        } ${className}`}
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={ariaLabel}
        title={title ?? selectedOption?.title ?? displayLabel}
        onClick={() => {
          if (disabled) return
          setOpen((prev) => !prev)
        }}
        onKeyDown={handleTriggerKeyDown}
      >
        <span className="studio-select-trigger-text">{displayLabel}</span>
        <svg
          className="studio-select-chevron"
          width="12"
          height="12"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2.2"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </button>

      {open &&
        menuPos &&
        createPortal(
          <div
            ref={menuRef}
            className={`studio-select-popover ${menuPos.openAbove ? 'is-above' : 'is-below'} ${menuClassName}`}
            role="listbox"
            aria-label={ariaLabel ?? placeholder}
            style={{
              left: menuPos.left,
              top: menuPos.top,
              width: menuPos.width,
              zIndex
            }}
            onMouseDown={(e) => e.stopPropagation()}
          >
            <div className="studio-select-list" style={{ maxHeight: menuPos.maxHeight }}>
              {options.length === 0 ? (
                <div className="studio-select-empty">{emptyText}</div>
              ) : (
                options.map((opt, idx) => {
                  const isSelected = opt.value === value
                  const isHighlighted = idx === highlightIndex
                  return (
                    <button
                      key={opt.value}
                      type="button"
                      role="option"
                      aria-selected={isSelected}
                      disabled={opt.disabled}
                      title={opt.title ?? opt.label}
                      className={`studio-select-option ${isSelected ? 'is-selected' : ''} ${
                        isHighlighted ? 'is-highlighted' : ''
                      }`}
                      onMouseEnter={() => setHighlightIndex(idx)}
                      onClick={() => handleSelect(opt)}
                    >
                      <div className="studio-select-option-main">
                        <div className="studio-select-option-top">
                          <span className="studio-select-option-label">{opt.label}</span>
                          {opt.badges && opt.badges.length > 0 && (
                            <span className="studio-select-option-badges">
                              {opt.badges.map((b) => (
                                <span key={b} className="studio-select-badge">
                                  {b}
                                </span>
                              ))}
                            </span>
                          )}
                        </div>
                        {opt.sublabel && (
                          <div className="studio-select-option-sub">{opt.sublabel}</div>
                        )}
                      </div>
                      <span className="studio-select-check" aria-hidden="true">
                        {isSelected && (
                          <svg
                            width="13"
                            height="13"
                            viewBox="0 0 24 24"
                            fill="none"
                            stroke="currentColor"
                            strokeWidth="2.4"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                          >
                            <polyline points="20 6 9 17 4 12" />
                          </svg>
                        )}
                      </span>
                    </button>
                  )
                })
              )}
            </div>
            {footerAction && (
              <div className="studio-select-footer">
                <button
                  type="button"
                  className="studio-select-footer-btn"
                  onClick={() => {
                    setOpen(false)
                    footerAction.onClick()
                  }}
                >
                  {footerAction.label}
                </button>
              </div>
            )}
          </div>,
          document.body
        )}
    </>
  )
}
