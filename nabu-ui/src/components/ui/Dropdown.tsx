'use client';

import React, {useEffect, useRef, useState} from 'react';
import {createPortal} from 'react-dom';
import {Check, ChevronDown, Search} from 'lucide-react';

export interface DropdownOption {
    id: string | number;
    name: string;
}

interface DropdownProps {
    options: DropdownOption[];
    value: string | number | (string | number)[];
    onChange: (value: string | number | (string | number)[]) => void;
    placeholder?: string;
    label?: string;
    required?: boolean;
    disabled?: boolean;
    className?: string;
    icon?: React.ReactNode;
    showSearch?: boolean;
    multiple?: boolean;
}

export const Dropdown: React.FC<DropdownProps> = ({
    options,
    value,
    onChange,
    placeholder = 'Select option',
    label,
    required = false,
    disabled = false,
    className = '',
    icon,
    showSearch,
    multiple = false,
}) => {
    const [isOpen, setIsOpen] = useState(false);
    const [searchTerm, setSearchTerm] = useState('');
    const [mounted, setMounted] = useState(false);
    const containerRef = useRef<HTMLDivElement>(null);
    const [coords, setCoords] = useState({ top: 0, left: 0, width: 0, ready: false });

    const effectiveShowSearch = showSearch || options.length > 10;

    useEffect(() => {
        setMounted(true);
    }, []);

    useEffect(() => {
        if (!isOpen) {
            setSearchTerm('');
            setCoords(prev => ({ ...prev, ready: false }));
        }
    }, [isOpen]);

    const isSelected = (id: string | number) => {
        if (multiple && Array.isArray(value)) {
            return value.some(v => v?.toString() === id.toString());
        }
        return value?.toString() === id.toString();
    };

    const selectedOptions = multiple && Array.isArray(value)
        ? options.filter(opt => (value as (string | number)[]).some(v => v?.toString() === opt.id.toString()))
        : options.filter(opt => opt.id.toString() === value?.toString());

    const updateCoords = () => {
        if (containerRef.current) {
            const rect = containerRef.current.getBoundingClientRect();
            const spaceBelow = window.innerHeight - rect.bottom;
            const dropdownHeight = Math.min(options.length * 40 + (effectiveShowSearch ? 50 : 0), 240);

            let top = rect.bottom + window.scrollY;
            if (spaceBelow < dropdownHeight + 20 && rect.top > dropdownHeight + 20) {
                top = rect.top + window.scrollY - dropdownHeight - 8;
            }

            setCoords({
                top,
                left: rect.left + window.scrollX,
                width: rect.width,
                ready: true,
            });
        }
    };

    useEffect(() => {
        if (isOpen) {
            updateCoords();
            window.addEventListener('resize', updateCoords);
            window.addEventListener('scroll', updateCoords, true);
        }
        return () => {
            window.removeEventListener('resize', updateCoords);
            window.removeEventListener('scroll', updateCoords, true);
        };
    }, [isOpen]);

    useEffect(() => {
        const handleClickOutside = (event: MouseEvent) => {
            if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
                let target = event.target as HTMLElement;
                while (target) {
                    if (target.dataset?.portal === 'dropdown') return;
                    target = target.parentElement as HTMLElement;
                }
                setIsOpen(false);
            }
        };
        if (isOpen) {
            document.addEventListener('mousedown', handleClickOutside);
        }
        return () => document.removeEventListener('mousedown', handleClickOutside);
    }, [isOpen]);

    const filteredOptions = options.filter(opt =>
        opt.name.toLowerCase().includes(searchTerm.toLowerCase())
    );

    const handleSelect = (option: DropdownOption) => {
        if (multiple) {
            const currentValues = Array.isArray(value) ? [...(value as (string | number)[])] : [];
            const index = currentValues.findIndex(v => v?.toString() === option.id.toString());
            if (index > -1) {
                currentValues.splice(index, 1);
            } else {
                currentValues.push(option.id);
            }
            onChange(currentValues);
        } else {
            onChange(option.id);
            setIsOpen(false);
            setSearchTerm('');
        }
    };

    return (
        <div className={`relative w-full ${className}`} ref={containerRef}>
            {label && (
                <label className="mb-2 block text-sm font-semibold text-foreground">
                    {label}{required && <span className="text-destructive"> *</span>}
                </label>
            )}

            <div className="relative">
                {icon && (
                    <div className="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 text-muted-foreground">
                        {icon}
                    </div>
                )}
                <button
                    type="button"
                    disabled={disabled}
                    onClick={() => setIsOpen(!isOpen)}
                    className={`flex h-9 w-full items-center justify-between gap-2 rounded-xl border border-border bg-card px-3 text-left outline-none transition-all duration-200 focus:ring-2 focus:ring-primary/30 disabled:cursor-not-allowed disabled:opacity-50 hover:bg-secondary cursor-pointer ${icon ? 'pl-10' : ''}`}
                >
                    <div className="flex flex-wrap gap-1 overflow-hidden">
                        {selectedOptions.length > 0 ? (
                            multiple ? (
                                selectedOptions.map(opt => (
                                    <span key={opt.id} className="inline-flex items-center rounded-md bg-primary/10 px-2 py-0.5 text-xs font-semibold text-primary">
                                        {opt.name}
                                    </span>
                                ))
                            ) : (
                                <span className="block truncate text-xs font-medium text-foreground">
                                    {selectedOptions[0].name}
                                </span>
                            )
                        ) : (
                            <span className="truncate text-xs text-muted-foreground">{placeholder}</span>
                        )}
                    </div>
                    <ChevronDown
                        className={`h-4 w-4 shrink-0 text-muted-foreground transition-transform duration-200 ${isOpen ? 'rotate-180' : ''}`}
                    />
                </button>
            </div>

            {mounted && isOpen && coords.ready && createPortal(
                <div
                    data-portal="dropdown"
                    style={{
                        position: 'fixed',
                        top: `${coords.top + (coords.top > (containerRef.current?.getBoundingClientRect().top ?? 0) + window.scrollY ? 4 : -4) - window.scrollY}px`,
                        left: `${coords.left - window.scrollX}px`,
                        minWidth: `${coords.width}px`,
                        maxWidth: 'max-content',
                        zIndex: 400,
                    }}
                    className="animate-dropdown-in overflow-hidden rounded-xl border border-border bg-card shadow-xl"
                >
                    {effectiveShowSearch && (
                        <div className="border-b border-border p-2">
                            <div className="relative">
                                <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                                <input
                                    autoFocus
                                    type="text"
                                    value={searchTerm}
                                    onChange={(e) => setSearchTerm(e.target.value)}
                                    placeholder="Search..."
                                    className="w-full rounded-lg border border-border bg-secondary/50 py-2 pl-10 pr-4 text-sm text-foreground outline-none placeholder:text-muted-foreground focus:ring-2 focus:ring-primary/30 transition-all"
                                />
                            </div>
                        </div>
                    )}
                    <div className="max-h-60 overflow-y-auto">
                        {filteredOptions.length > 0 ? (
                            filteredOptions.map((option) => (
                                <button
                                    key={option.id}
                                    type="button"
                                    onClick={() => handleSelect(option)}
                                    className={`flex w-full items-center justify-between gap-4 px-4 py-2.5 text-left text-sm transition-colors hover:bg-primary/5 ${
                                        isSelected(option.id)
                                            ? 'bg-primary/10 font-semibold text-primary'
                                            : 'text-foreground'
                                    }`}
                                >
                                    <span className="whitespace-normal break-words">{option.name}</span>
                                    {isSelected(option.id) && <Check className="h-4 w-4 shrink-0" />}
                                </button>
                            ))
                        ) : (
                            <div className="px-4 py-8 text-center text-sm text-muted-foreground">
                                No results found
                            </div>
                        )}
                    </div>
                </div>,
                document.body
            )}
        </div>
    );
};
