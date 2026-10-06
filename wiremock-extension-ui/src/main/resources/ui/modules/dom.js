/**
 * DOM query cache and UI string utilities.
 * Contract: IDs match test selectors in UiPlaywrightTest and index.html.
 */

export const elements = {
  // Navigation & Metrics Badges
  tabs: document.querySelectorAll('.tab, .nav-tab'),
  statStubs: document.getElementById('stat-stubs'),
  statRequests: document.getElementById('stat-requests'),
  statScenarios: document.getElementById('stat-scenarios'),
  stubsCountLabel: document.getElementById('stubs-count-label'),
  journalCount: document.getElementById('journal-count'),

  // Stubs Tab
  searchBox: document.getElementById('search-box'),
  projectFilterSelect: document.getElementById('project-filter-select'),
  projectLifecycleBar: document.getElementById('project-lifecycle-bar'),
  projectModeBadge: document.getElementById('project-mode-badge'),
  projectActiveName: document.getElementById('project-active-name'),
  btnProjectModeToggle: document.getElementById('btn-project-mode-toggle'),
  btnProjectSnapshot: document.getElementById('btn-project-snapshot'),
  btnFilterAll: document.getElementById('btn-filter-all'),
  btnFilterActive: document.getElementById('btn-filter-active'),
  btnFilterDisabled: document.getElementById('btn-filter-disabled'),
  countFilterAll: document.getElementById('count-filter-all'),
  countFilterActive: document.getElementById('count-filter-active'),
  countFilterDisabled: document.getElementById('count-filter-disabled'),
  chkSelectAll: document.getElementById('chk-select-all'),
  stubList: document.getElementById('stub-list'),
  stubDetailEmpty: document.getElementById('stub-detail-empty'),
  stubDetailView: document.getElementById('stub-detail-view'),
  detailMethod: document.getElementById('detail-method'),
  detailUrl: document.getElementById('detail-url'),
  detailName: document.getElementById('detail-name'),
  detailStatus: document.getElementById('detail-status'),
  detailPriority: document.getElementById('detail-priority'),
  detailScenario: document.getElementById('detail-scenario'),
  detailProject: document.getElementById('detail-project'),
  detailLifecycleState: document.getElementById('detail-lifecycle-state'),
  stubJsonViewer: document.getElementById('stub-json-viewer'),
  btnRefresh: document.getElementById('btn-refresh'),
  btnRefreshStubs: document.getElementById('btn-refresh-stubs'),
  btnCreateStub: document.getElementById('btn-create-stub'),
  btnExportStubs: document.getElementById('btn-export-stubs'),
  btnTestStub: document.getElementById('btn-test-stub'),
  btnEditStub: document.getElementById('btn-edit-stub'),
  btnCloneStub: document.getElementById('btn-clone-stub'),
  btnToggleStub: document.getElementById('btn-toggle-stub'),
  btnDeleteStub: document.getElementById('btn-delete-stub'),
  btnViewStubInJournal: document.getElementById('btn-view-stub-in-journal'),
  btnCopyCurl: document.getElementById('btn-copy-curl'),
  btnCopyJson: document.getElementById('btn-copy-json'),

  // Bulk Actions
  bulkActionsBar: document.getElementById('bulk-actions-bar'),
  bulkSelectedCount: document.getElementById('bulk-selected-count'),
  btnBulkEnable: document.getElementById('btn-bulk-enable'),
  btnBulkDisable: document.getElementById('btn-bulk-disable'),
  btnBulkExport: document.getElementById('btn-bulk-export'),
  btnBulkDelete: document.getElementById('btn-bulk-delete'),

  // Stub Editor Modal
  stubEditorModal: document.getElementById('stub-editor-modal'),
  btnCancelEditor: document.getElementById('btn-cancel-editor'),
  btnSaveEditor: document.getElementById('btn-save-editor'),
  btnCloseEditorModal: document.getElementById('btn-close-editor-modal'),
  btnEditorFormatJson: document.getElementById('btn-editor-format-json'),

  // Unified Import Modal
  btnOpenOpenApiModal: document.getElementById('btn-open-openapi-modal'),
  openapiModal: document.getElementById('openapi-modal'),
  btnCloseOpenApiModal: document.getElementById('btn-close-openapi-modal'),
  btnCancelOpenApi: document.getElementById('btn-cancel-openapi'),
  importTabOpenApi: document.getElementById('import-tab-openapi'),
  importTabBundle: document.getElementById('import-tab-bundle'),
  importViewOpenApi: document.getElementById('import-view-openapi'),
  importViewBundle: document.getElementById('import-view-bundle'),
  btnBrowseOpenApiFile: document.getElementById('btn-browse-openapi-file'),
  openapiFileInput: document.getElementById('openapi-file-input'),
  openapiDropzone: document.getElementById('openapi-dropzone'),
  openapiSpecContent: document.getElementById('openapi-spec-content'),
  optOpenApiModeSynthetic: document.getElementById('opt-openapi-mode-synthetic'),
  optOpenApiModeProxy: document.getElementById('opt-openapi-mode-proxy'),
  openapiProxyConfig: document.getElementById('openapi-proxy-config'),
  openapiProxyUrl: document.getElementById('openapi-proxy-url'),
  openapiServerSelect: document.getElementById('openapi-server-select'),
  openapiProjectName: document.getElementById('openapi-project-name'),
  openapiProxyHeaders: document.getElementById('openapi-proxy-headers'),
  btnSubmitOpenApiImport: document.getElementById('btn-submit-openapi-import'),
  optOpenApiSuccessOnly: document.getElementById('opt-openapi-success-only'),
  optOpenApiMatchHeaders: document.getElementById('opt-openapi-match-headers'),
  optOpenApiMatchQueries: document.getElementById('opt-openapi-match-queries'),
  openapiImportFeedback: document.getElementById('openapi-import-feedback'),
  bundleFileInput: document.getElementById('bundle-file-input'),
  btnBrowseBundleFile: document.getElementById('btn-browse-bundle-file'),
  bundleDropzone: document.getElementById('bundle-dropzone'),
  bundleContent: document.getElementById('bundle-content'),
  bundleConflictPolicy: document.getElementById('bundle-conflict-policy'),
  bundleTargetProject: document.getElementById('bundle-target-project'),
  btnSubmitBundleImport: document.getElementById('btn-submit-bundle-import'),
  bundleImportFeedback: document.getElementById('bundle-import-feedback'),

  // Journal Tab
  journalContextBanner: document.getElementById('journal-context-banner'),
  journalFilterChipText: document.getElementById('journal-filter-chip-text'),
  btnJournalClearStubFilter: document.getElementById('btn-journal-clear-stub-filter'),
  journalList: document.getElementById('journal-list'),
  journalSearch: document.getElementById('journal-search'),
  journalAutoRefresh: document.getElementById('journal-auto-refresh'),
  journalFilteredCount: document.getElementById('journal-filtered-count'),
  filterUnmatchedOnly: document.getElementById('filter-unmatched-only'),
  btnRefreshJournal: document.getElementById('btn-refresh-journal'),
  btnResetJournal: document.getElementById('btn-reset-journal'),

  // Tester Tab
  testerContextBanner: document.getElementById('tester-context-banner'),
  testerContextTitle: document.getElementById('tester-context-title'),
  btnTesterResetStub: document.getElementById('btn-tester-reset-stub'),
  btnTesterClearContext: document.getElementById('btn-tester-clear-context'),
  testerMethod: document.getElementById('tester-method'),
  testerUrl: document.getElementById('tester-url'),
  testerHeaders: document.getElementById('tester-headers'),
  testerBody: document.getElementById('tester-body'),
  btnTesterSend: document.getElementById('btn-tester-send'),
  btnTesterFormatJson: document.getElementById('btn-tester-format-json'),
  btnTesterCopyCurl: document.getElementById('btn-tester-copy-curl'),
  btnTesterShareLink: document.getElementById('btn-tester-share-link'),
  btnTesterResetForm: document.getElementById('btn-tester-reset-form'),
  testerResponseStatus: document.getElementById('tester-response-status'),
  testerResponseTime: document.getElementById('tester-response-time'),
  testerResponseSize: document.getElementById('tester-response-size'),
  testerResponseHeaders: document.getElementById('tester-response-headers'),
  testerResponseHeaderCount: document.getElementById('tester-response-header-count'),
  testerResponseBody: document.getElementById('tester-response-body'),
  btnViewHighlighted: document.getElementById('btn-view-highlighted'),
  btnViewRaw: document.getElementById('btn-view-raw'),
  btnTesterCopyResponse: document.getElementById('btn-tester-copy-response'),
  testerHistoryPanel: document.getElementById('tester-history-panel'),
  testerHistoryList: document.getElementById('tester-history-list'),
  testerHistoryCount: document.getElementById('tester-history-count'),
  btnTesterClearHistory: document.getElementById('btn-tester-clear-history'),
  btnToggleTesterHistory: document.getElementById('btn-toggle-tester-history'),
  btnExpandTesterHistory: document.getElementById('btn-expand-tester-history'),
  testerHistoryCollapsedBar: document.getElementById('tester-history-collapsed-bar'),
  testerHistoryCollapsedCount: document.getElementById('tester-history-collapsed-count'),
  btnPresetJson: document.getElementById('btn-preset-json'),
  btnPresetBearer: document.getElementById('btn-preset-bearer'),
  btnPresetAccept: document.getElementById('btn-preset-accept'),
  btnTesterTabResponse: document.getElementById('btn-tester-tab-response'),
  btnTesterTabRequest: document.getElementById('btn-tester-tab-request'),
  btnTesterTabBoth: document.getElementById('btn-tester-tab-both'),
  testerViewResponse: document.getElementById('tester-view-response'),
  testerViewRequest: document.getElementById('tester-view-request'),
  testerSentMethod: document.getElementById('tester-sent-method'),
  testerSentUrl: document.getElementById('tester-sent-url'),
  testerSentHeaders: document.getElementById('tester-sent-headers'),
  testerSentBody: document.getElementById('tester-sent-body'),

  // Scenarios Tab
  scenariosContainer: document.getElementById('scenarios-container'),
  btnRefreshScenarios: document.getElementById('btn-refresh-scenarios'),
  btnResetScenarios: document.getElementById('btn-reset-scenarios')
};

/** Escapes HTML special characters for safe innerHTML injection. */
export function escapeHtml(str) {
  if (str === null || str === undefined) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

/** Formats byte count into human-readable string (B, KB, MB). */
export function formatBytes(bytes) {
  if (bytes === 0) return '0 B';
  if (!bytes || isNaN(bytes)) return '';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}
