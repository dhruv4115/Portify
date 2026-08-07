/**
 * The English catalogue — the source of truth for every string in the UI.
 *
 * <p>Two rules keep this file honest:
 *
 * <ol>
 *   <li>Every other language is typed as `Partial<Catalog>`, so this file defines the key set and
 *       nothing else can invent one. A missing translation falls back to the sentence here.
 *   <li>Placeholders are named (`{name}`, `{code}`), never positional. Word order changes between
 *       languages; a positional `%s` would force translators to reorder arguments they cannot see.
 * </ol>
 *
 * <p>Amounts, percentages and dates are never assembled here. They come from `lib/formatMoney.ts`
 * already rendered, and are interpolated as opaque strings — the decimal-string discipline that
 * file documents would be lost if a catalogue tried to format a number itself.
 */

export const en = {
  // ── Brand and chrome ──────────────────────────────────────────────────────
  'app.name': 'Protify',
  'app.tagline': 'Multi-currency portfolio management',
  'app.description':
    'Track holdings, performance and allocation across currencies — with every figure priced honestly, including when the market is closed.',

  // ── Navigation ────────────────────────────────────────────────────────────
  'nav.overview': 'Overview',
  'nav.portfolios': 'Portfolios',
  'nav.settings': 'Settings',
  'nav.about': 'About',
  'nav.primary': 'Primary',
  'nav.openMenu': 'Open menu',
  'nav.closeMenu': 'Close menu',
  'nav.skipToContent': 'Skip to content',
  'nav.breadcrumb': 'Breadcrumb',

  // ── Header actions ────────────────────────────────────────────────────────
  'header.search': 'Search',
  'header.searchHint': 'Search portfolios and actions',
  'header.account': 'Account',
  'header.signOut': 'Sign out',
  'header.signedIn': 'Signed in',

  // ── Theme ─────────────────────────────────────────────────────────────────
  'theme.label': 'Theme',
  'theme.toggle': 'Switch theme',
  'theme.light': 'Light',
  'theme.dark': 'Dark',
  'theme.system': 'System',
  'theme.systemHint': 'Follows your device setting',

  // ── Language ──────────────────────────────────────────────────────────────
  'language.label': 'Language',
  'language.change': 'Change language',
  'language.current': 'Current language: {name}',
  'language.hint':
    'Translates the interface. Your data, amounts and currency codes are never translated.',
  'language.sourceNote': 'English is the source language. Untranslated text falls back to English.',
  'language.detected': 'Detected from your browser',

  // ── Command palette ───────────────────────────────────────────────────────
  'palette.title': 'Command palette',
  'palette.placeholder': 'Search portfolios, jump to a page, run a command…',
  'palette.empty': 'No matches.',
  'palette.emptyHint': 'Try a portfolio name, a page, or a command like “theme”.',
  'palette.group.portfolios': 'Portfolios',
  'palette.group.navigation': 'Go to',
  'palette.group.actions': 'Actions',
  'palette.group.language': 'Language',
  'palette.open': 'Open command palette',
  'palette.hintNavigate': 'to navigate',
  'palette.hintSelect': 'to select',
  'palette.hintClose': 'to close',
  'palette.action.newPortfolio': 'Create a new portfolio',
  'palette.action.toggleTheme': 'Toggle light / dark theme',
  'palette.action.signOut': 'Sign out',
  'palette.action.settings': 'Open settings',

  // ── Sign in ───────────────────────────────────────────────────────────────
  'signIn.heading': 'Sign in to Protify',
  'signIn.subheading': 'Sign in with Google to open your portfolios.',
  'signIn.tokenLabel': 'Google ID token',
  'signIn.tokenHint': 'Set VITE_GOOGLE_CLIENT_ID in .env.local for the real sign-in button.',
  'signIn.continue': 'Continue',
  'signIn.feature.multiCurrency': 'Multi-currency by design',
  'signIn.feature.multiCurrencyBody':
    'Hold dollars, rupees, euros and pounds side by side. Every total is converted at the rate that applied on the day.',
  'signIn.feature.honest': 'Honest pricing',
  'signIn.feature.honestBody':
    'When a price or rate is stale, the number says so instead of quietly pretending to be live.',
  'signIn.feature.analytics': 'Real analytics',
  'signIn.feature.analyticsBody':
    'Time-weighted return, drawdown and allocation — the figures that separate a tracker from a spreadsheet.',

  // ── Overview ──────────────────────────────────────────────────────────────
  'overview.title': 'Overview',
  'overview.greeting': 'Welcome back',
  'overview.subtitle': 'Everything you hold, across every portfolio.',
  'overview.totalsByCurrency': 'Total value',
  'overview.totalsByCurrencyHint':
    'Grouped by base currency. Portfolios in different currencies are never added together.',
  'overview.portfolioCount': 'Portfolios',
  'overview.holdingCount': 'Holdings',
  'overview.currencyCount': 'Currencies',
  'overview.bestPerformer': 'Best performer',
  'overview.worstPerformer': 'Worst performer',
  'overview.allPortfolios': 'Your portfolios',
  'overview.viewAll': 'View all',
  'overview.empty': 'Nothing to show yet.',
  'overview.emptyHint': 'Create your first portfolio and this page fills in.',
  'overview.mixedCurrencyNote':
    'Showing {count} base currencies. Totals are reported per currency — no cross-currency sum is invented here.',

  // ── Portfolio list ────────────────────────────────────────────────────────
  'portfolios.title': 'Portfolios',
  'portfolios.subtitle': 'Every portfolio you own, with its current value and unrealised P&L.',
  'portfolios.new': 'New portfolio',
  'portfolios.empty': 'No portfolios yet.',
  'portfolios.emptyHint': 'A portfolio is the container everything else lives in.',
  'portfolios.createFirst': 'Create your first one',
  'portfolios.loadError': 'Could not load your portfolios.',
  'portfolios.search': 'Search portfolios',
  'portfolios.searchPlaceholder': 'Search by name…',
  'portfolios.noMatches': 'No portfolios match “{query}”.',
  'portfolios.clearSearch': 'Clear search',
  'portfolios.sortBy': 'Sort by',
  'portfolios.sort.name': 'Name',
  'portfolios.sort.value': 'Value',
  'portfolios.sort.pnl': 'P&L',
  'portfolios.sort.holdings': 'Holdings',
  'portfolios.view.grid': 'Grid view',
  'portfolios.view.list': 'List view',
  'portfolios.count': '{count} portfolios',
  'portfolios.countOne': '1 portfolio',
  'portfolios.holdings': '{count} holdings',
  'portfolios.holdingsOne': '1 holding',

  // ── Create portfolio form ─────────────────────────────────────────────────
  'createPortfolio.title': 'New portfolio',
  'createPortfolio.subtitle': 'Name it and pick the currency everything will be reported in.',
  'createPortfolio.name': 'Name',
  'createPortfolio.namePlaceholder': 'Retirement, Growth, Long-term…',
  'createPortfolio.baseCurrency': 'Base currency',
  'createPortfolio.baseCurrencyHint':
    'Everything is reported in this currency. You can change it later.',
  'createPortfolio.submit': 'Create portfolio',
  'createPortfolio.submitting': 'Creating…',
  'createPortfolio.created': 'Created {name}',

  // ── Portfolio detail ──────────────────────────────────────────────────────
  'portfolio.back': 'Portfolios',
  'portfolio.totalValue': 'Total value',
  'portfolio.unrealisedPnl': 'Unrealised P&L',
  'portfolio.realisedPnl': 'Realised P&L',
  'portfolio.cash': 'Cash',
  'portfolio.marketValue': 'Market value',
  'portfolio.costBasis': 'Cost basis',
  'portfolio.baseCurrency': 'Base currency',
  'portfolio.notFound': 'That portfolio does not exist.',
  'portfolio.loadError': 'Could not load this portfolio.',
  'portfolio.backToPortfolios': 'Back to portfolios',
  'portfolio.currencySwitched': 'Base currency switched to {code}',
  'portfolio.currencySwitchFailed': 'Could not switch base currency.',
  'portfolio.refresh': 'Refresh',
  'portfolio.refreshed': 'Refreshed',
  'portfolio.sections': 'Sections',
  'portfolio.updated': 'Updated {when}',

  // ── Performance ───────────────────────────────────────────────────────────
  'performance.title': 'Performance',
  'performance.empty': 'No performance history yet. Add a transaction and the chart starts from its date.',
  'performance.singlePoint': 'One day of history so far — the line fills in from tomorrow.',
  'performance.totalValue': 'Total value',
  'performance.carriedForward': 'carried forward — market closed',
  'performance.range': 'Time range',
  'performance.range.1M': '1M',
  'performance.range.3M': '3M',
  'performance.range.6M': '6M',
  'performance.range.YTD': 'YTD',
  'performance.range.1Y': '1Y',
  'performance.range.ALL': 'All',
  'performance.interval': 'Interval',
  'performance.interval.DAILY': 'Daily',
  'performance.interval.WEEKLY': 'Weekly',
  'performance.interval.MONTHLY': 'Monthly',
  'performance.loadError': 'Could not load performance for that range.',
  'performance.startValue': 'Start',
  'performance.endValue': 'End',
  'performance.netContributions': 'Net contributions',
  'performance.netContributionsHint':
    'Money you paid in minus money you took out. It moves the total value without being a gain or a loss.',

  // ── Allocation ────────────────────────────────────────────────────────────
  'allocation.title': 'Allocation',
  'allocation.groupBy': 'Group allocation by',
  'allocation.by.CURRENCY': 'By currency',
  'allocation.by.ASSET_TYPE': 'By asset type',
  'allocation.by.SECTOR': 'By sector',
  'allocation.by.INSTRUMENT': 'By instrument',
  'allocation.empty': 'No priced holdings yet. Allocation appears once a position has a market value.',
  'allocation.loadError': 'Could not load allocation.',
  'allocation.total': 'Total',
  'allocation.slices': '{count} groups',
  'allocation.other': 'Other ({count})',
  'allocation.otherHint': 'The {count} smallest are grouped. Select "Other" to list them.',
  'allocation.cashExcluded': 'Cash is reported separately and is not included in this breakdown.',

  // ── Analytics ─────────────────────────────────────────────────────────────
  'analytics.title': 'Analytics',
  'analytics.empty': 'Not enough performance history yet — analytics need at least two priced days.',
  'analytics.twr': 'TWR (time-weighted return)',
  'analytics.twrHint':
    'Time-weighted return: gains and losses from the market only — money you added or withdrew is never counted as a gain or a loss.',
  'analytics.annualised': 'Annualised return',
  'analytics.annualisedHint': 'The time-weighted return restated as a per-year rate.',
  'analytics.maxDrawdown': 'Max drawdown',
  'analytics.maxDrawdownHint': 'The deepest fall from a peak before a new peak was reached.',
  'analytics.bestDay': 'Best day',
  'analytics.worstDay': 'Worst day',

  // ── Insights ──────────────────────────────────────────────────────────────
  'insights.title': 'Insights',
  'insights.empty': "Insights aren't available for this portfolio yet.",
  'insights.engine.AI_GENERATED': 'AI-generated',
  'insights.engine.RULE_BASED': 'Rule-based summary',
  'insights.engineLabel': 'Summary source',
  // Short forms for the toggle, where the full labels do not fit. The full ones stay as the
  // buttons' `title`, so the abbreviation is never the only explanation offered.
  'insights.engine.short.AI_GENERATED': 'AI',
  'insights.engine.short.RULE_BASED': 'Rule-based',
  'insights.generatedAt': 'Generated {when}',

  // ── Holdings ──────────────────────────────────────────────────────────────
  'holdings.title': 'Holdings',
  'holdings.empty': 'Nothing here yet. Add your first transaction to start tracking this portfolio.',
  'holdings.noMatches': 'No holdings match “{query}”.',
  'holdings.search': 'Search holdings',
  'holdings.searchPlaceholder': 'Filter by symbol or name…',
  'holdings.symbol': 'Symbol',
  'holdings.quantity': 'Quantity',
  'holdings.avgCost': 'Avg cost',
  'holdings.lastPrice': 'Last price',
  'holdings.value': 'Value',
  'holdings.pnl': 'P&L',
  'holdings.weight': 'Weight',
  'holdings.native': 'native',
  'holdings.base': 'base',
  'holdings.count': '{count} positions',
  'holdings.countOne': '1 position',

  // ── Transactions ──────────────────────────────────────────────────────────
  'transactions.title': 'Transactions',
  'transactions.empty': 'No transactions recorded yet.',
  'transactions.noMatches': 'No transactions match your filters.',
  'transactions.search': 'Search transactions',
  'transactions.searchPlaceholder': 'Filter by symbol or note…',
  'transactions.filterType': 'Filter by type',
  'transactions.allTypes': 'All types',
  'transactions.date': 'Date',
  'transactions.type': 'Type',
  'transactions.instrument': 'Instrument',
  'transactions.quantity': 'Quantity',
  'transactions.price': 'Price',
  'transactions.total': 'Total',
  'transactions.fxOnDay': 'FX on the day',
  'transactions.actions': 'Actions',
  'transactions.cash': 'cash',
  'transactions.delete': 'Delete',
  'transactions.deleteLabel': 'Delete {type} on {date}',
  'transactions.deleted': 'Transaction deleted',
  'transactions.deleteFailed': 'Could not delete the transaction.',
  'transactions.showing': 'Showing {shown} of {total}',
  'transactions.loadMore': 'Load more',
  'transactions.loadingMore': 'Loading…',
  'transactions.clearFilters': 'Clear filters',

  // ── Delete dialog ─────────────────────────────────────────────────────────
  'deleteDialog.title': 'Delete this transaction?',
  'deleteDialog.body':
    'Holdings and the performance chart are rebuilt from what is left, so the numbers will move.',
  'deleteDialog.confirm': 'Delete',
  'deleteDialog.cancel': 'Cancel',

  // ── Add transaction form ──────────────────────────────────────────────────
  'txn.title': 'Add a transaction',
  'txn.subtitle': 'Buys, sells, dividends, cash movements and fees.',
  'txn.type': 'Type',
  'txn.symbol': 'Symbol',
  'txn.symbolPlaceholder': 'Start typing to search…',
  'txn.quantity': 'Quantity',
  'txn.price': 'Price',
  'txn.amount': 'Amount',
  'txn.currency': 'Currency',
  'txn.fees': 'Fees',
  'txn.date': 'Date',
  'txn.note': 'Note',
  'txn.notePlaceholder': 'Optional',
  'txn.submit': 'Add transaction',
  'txn.submitting': 'Adding…',
  'txn.suggestions': 'Matching instruments',
  'txn.unreachable': 'Could not reach the server.',
  'txn.type.BUY': 'BUY',
  'txn.type.SELL': 'SELL',
  'txn.type.DIVIDEND': 'DIVIDEND',
  'txn.type.DEPOSIT': 'DEPOSIT',
  'txn.type.WITHDRAWAL': 'WITHDRAWAL',
  'txn.type.FEE': 'FEE',

  // ── Export ────────────────────────────────────────────────────────────────
  'export.title': 'Export',
  'export.subtitle': 'Take your data with you — plain CSV, no lock-in.',
  'export.holdings': 'Export holdings (CSV)',
  'export.transactions': 'Export transactions (CSV)',
  'export.exporting': 'Exporting…',
  'export.failed': 'Could not export transactions.',

  // ── Import ────────────────────────────────────────────────────────────────
  'import.title': 'Import transactions',
  'import.subtitle':
    'Add transactions from a CSV file. Your holdings are recalculated from them automatically.',
  'import.choose': 'Choose CSV file',
  'import.checking': 'Checking…',
  'import.clear': 'Clear',
  'import.rowsRead': '— {count} rows read.',
  'import.valid': '{count} transactions are ready to import.',
  'import.rejected': 'Nothing was imported. {count} rows need fixing first:',
  'import.line': 'Line {line}',
  'import.confirm': 'Import {count} transactions',
  'import.importing': 'Importing…',
  'import.done': 'Imported {count} transactions.',
  'import.failed': 'Could not import the file.',
  'import.format':
    'Columns: Date, Type, Symbol, Quantity, Price, Currency, Fees, Note. Date, Type, Price and Currency are required; anything else is ignored, so a file exported above can be imported here unchanged. Either every row imports or none does.',

  // ── Settings ──────────────────────────────────────────────────────────────
  'settings.title': 'Settings',
  'settings.subtitle': 'How Protify looks and reads. Stored on this device only.',
  'settings.appearance': 'Appearance',
  'settings.appearanceHint': 'Pick a theme, or follow whatever your device is set to.',
  'settings.language': 'Language',
  'settings.density': 'Density',
  'settings.densityHint': 'How tightly rows and cards are packed. Text size is never changed.',
  'settings.density.comfortable': 'Comfortable',
  'settings.density.compact': 'Compact',
  'settings.motion': 'Motion',
  'settings.motionHint':
    'Animations are decorative. Turning them off changes nothing about what the app can do.',
  'settings.motion.full': 'Full animation',
  'settings.motion.reduced': 'Reduced motion',
  'settings.motionSystemNote': 'Your device already asks for reduced motion, which is always honoured.',
  'settings.reset': 'Reset to defaults',
  'settings.resetDone': 'Settings reset',
  'settings.storageNote':
    'These preferences live in this browser. They are not sent to the server and do not follow you to another device.',
  'settings.preview': 'Preview',
  'settings.previewHint': 'A live sample of the current settings.',

  // ── About ─────────────────────────────────────────────────────────────────
  'about.title': 'About Protify',
  'about.lede':
    'A portfolio manager built around one idea: a number you cannot trust is worse than no number at all.',
  'about.principles': 'Principles',
  'about.principle.exact': 'Exact money',
  'about.principle.exactBody':
    'Every amount travels from the server as a decimal string and is rendered without ever passing through a floating-point number. Rounding happens on the digits, so what you see is what the ledger holds.',
  'about.principle.honest': 'Honest degradation',
  'about.principle.honestBody':
    'When a price or an exchange rate is out of date, the figure is labelled stale and still shown. The app never blanks a screen it could have filled, and never presents old data as fresh.',
  'about.principle.currency': 'Currency as a first-class idea',
  'about.principle.currencyBody':
    'Cost and last price stay in each instrument’s own currency; value, P&L and weight are converted to the portfolio’s base. Every cell says which currency it is in, so a mistake would be visible rather than silent.',
  'about.principle.accessible': 'Accessible by construction',
  'about.principle.accessibleBody':
    'Full keyboard navigation, visible focus, motion that can be switched off, colour choices validated for colour-blind readers, and an interface that translates into nine languages.',
  'about.stack': 'Built with',
  'about.shortcuts': 'Keyboard shortcuts',
  'about.shortcut.palette': 'Open the command palette',
  'about.shortcut.theme': 'Toggle the theme',
  'about.shortcut.search': 'Focus search',
  'about.shortcut.close': 'Close a dialog or menu',

  // ── Footer ────────────────────────────────────────────────────────────────
  'footer.product': 'Product',
  'footer.resources': 'Resources',
  'footer.preferences': 'Preferences',
  'footer.rights': '© {year} Protify. Built for the Neueda portfolio management programme.',
  'footer.apiDocs': 'API contract',
  'footer.status': 'All systems normal',

  // ── Errors and empty states ───────────────────────────────────────────────
  'error.title': 'Something went wrong',
  'error.retry': 'Try again',
  'error.generic': 'Something did not work. Try again in a moment.',
  'notFound.title': 'Page not found',
  'notFound.body': 'The page you asked for does not exist, or it moved.',
  'notFound.home': 'Go to overview',

  // ── Data quality ──────────────────────────────────────────────────────────
  'quality.stale': 'stale · {date}',
  'quality.unpriced': 'unpriced',
  'quality.pricedOn': 'Priced {date}',
  'quality.pricedUnknown': 'Priced from an unknown date',

  // ── Generic ───────────────────────────────────────────────────────────────
  'common.cancel': 'Cancel',
  'common.close': 'Close',
  'common.save': 'Save',
  'common.loading': 'Loading…',
  'common.none': '—',
  'common.of': 'of',
  'common.and': 'and',
  'common.more': 'More',
  'common.less': 'Less',
  'common.all': 'All',
  'common.optional': 'optional',
} as const;

export type TranslationKey = keyof typeof en;

/**
 * Note the widening to `string`. `en` is `as const` so a typo in a key is caught, but the *values*
 * must be plain strings — otherwise `Partial<Catalog>` would demand that the Arabic catalogue's
 * 'nav.overview' be the literal `'Overview'`, which is the opposite of the point.
 */
export type Catalog = Record<TranslationKey, string>;
