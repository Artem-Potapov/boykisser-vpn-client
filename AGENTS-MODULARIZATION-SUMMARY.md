# AGENTS.md Modularization Summary

## Completed: 2026-09-02

Successfully split the monolithic AGENTS.md (58,008 chars) into three focused modules totaling 45,464 chars.

## File Breakdown

| File | Characters | Lines | Purpose |
|------|-----------|-------|---------|
| **AGENTS.md** | 9,150 | 211 | Main entry point: overview, quick commands, style guide, agent guardrails |
| **AGENTS-ARCHITECTURE.md** | 20,318 | 237 | Architecture diagrams, fail-closed security model, dormant features |
| **AGENTS-DEVELOPMENT.md** | 15,996 | 191 | Testing strategy, release builds, extensibility hooks |
| **Original (backup)** | 58,008 | 723 | Preserved as AGENTS.md.backup |

## Space Savings: ~12.5K chars

Achieved through:

1. **Eliminated redundancy**: Architecture + Security sections duplicated fail-closed explanations (~3-4K saved)
2. **Compressed Repository Structure**: Reduced verbose package annotations by 70% (~4-5K saved)
3. **Table format for Extensibility Hooks**: Replaced prose with scannable tables (~4K saved)
4. **Removed**: "IMPORTANT NOTICE" section (redundant with system prompt), "Further Reading" bullets (inlined where relevant), stale TODO comments (~800 chars)

## All Files Under 40K Character Limit ✓

- AGENTS.md: 9.1K (23% of limit)
- AGENTS-ARCHITECTURE.md: 20.3K (51% of limit)
- AGENTS-DEVELOPMENT.md: 16.0K (40% of limit)

## Key Improvements

### Better Information Architecture

**Original structure**: Linear 723-line document where critical concepts scattered across multiple sections

**New structure**:
- **AGENTS.md** = "First contact & daily reference" — what agents need 80% of the time
- **AGENTS-ARCHITECTURE.md** = "How it works & what to preserve" — deep architectural understanding
- **AGENTS-DEVELOPMENT.md** = "How to modify & verify" — implementation and extension guide

### Unified Architecture + Security

The original document explained fail-closed guarantees in two separate places (Architecture Notes lines 302-344, Security & Compliance lines 430-542). New AGENTS-ARCHITECTURE.md unifies these into one coherent narrative under "Architecture & Fail-Closed Security" — showing that they're not separate concerns but one integrated system.

### Scannable Extension Points

Replaced verbose prose with structured tables:

**Before** (Extensibility Hooks section, ~7K prose):
```
Runtime config extension point: app/src/main/java/com/justme/xtls_core_proxy/config/ConfigBuilder.kt
(fromVlessUri, fromHysteria2Uri, fromJson, outbound/routing builders; buildRuntimeConfig(input, 
log = LogSettings(WARNING, null), tuning = TuningSettings.NONE) first obtains the protocol-specific 
secure base, then runs forceLog → applyFragmentation → applyMux → applyDns → applyRouting → 
applyCoreSettings...
```

**After** (table format, ~3K):
```
| What | File | Key Entry Point | Critical Constraints |
|------|------|-----------------|---------------------|
| Runtime Config Pipeline | config/ConfigBuilder.kt | buildRuntimeConfig() | Ordered: forceLog → fragmentation → mux → DNS → routing → core |
```

### Preserved All Critical Information

Despite aggressive compression, we preserved:
- All architectural constraints
- All fail-closed guarantees
- All extension points
- All necessary file references
- All load-bearing implementation details

What we removed:
- Duplicate explanations
- Verbose prose where tables/bullets work
- Meta-documentation about tool call formats (redundant with system prompt)

## Integration

Updated `/root/xtls-core-proxy/CLAUDE.md` to reference all three files:
```
@AGENTS.md
@AGENTS-ARCHITECTURE.md
@AGENTS-DEVELOPMENT.md
```

## Backup

Original file preserved at `/root/xtls-core-proxy/AGENTS.md.backup` for reference.

## Verification Checklist

- [x] All files under 40K character limit
- [x] No information loss on critical constraints
- [x] Cross-references use relative links
- [x] CLAUDE.md updated to load all three files
- [x] Original backed up
- [x] Clear navigation between files
- [x] Scannable formatting (tables for extension points)
- [x] Unified Architecture + Security narrative
- [x] Removed redundancy and stale content
