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
  stubList: document.getElementById('stub-list'),
  stubDetailEmpty: document.getElementById('stub-detail-empty'),
  stubDetailView: document.getElementById('stub-detail-view'),
  detailMethod: document.getElementById('detail-method'),
  detailUrl: document.getElementById('detail-url'),
  detailName: document.getElementById('detail-name'),
  detailStatus: document.getElementById('detail-status'),
  detailPriority: document.getElementById('detail-priority'),
  detailScenario: document.getElementById('detail-scenario'),
  stubJsonViewer: document.getElementById('stub-json-viewer'),
  btnRefresh: document.getElementById('btn-refresh'),
  btnRefreshStubs: document.getElementById('btn-refresh-stubs'),
  btnTestStub: document.getElementById('btn-test-stub'),
  btnViewStubInJournal: document.getElementById('btn-view-stub-in-journal'),
  btnCopyCurl: document.getElementById('btn-copy-curl'),
  btnCopyJson: document.getElementById('btn-copy-json'),

  // OpenAPI Import Modal
  btnOpenOpenApiModal: document.getElementById('btn-open-openapi-modal'),
  openapiModal: document.getElementById('openapi-modal'),
  btnCloseOpenApiModal: document.getElementById('btn-close-openapi-modal'),
  btnCancelOpenApi: document.getElementById('btn-cancel-openapi'),
  btnBrowseOpenApiFile: document.getElementById('btn-browse-openapi-file'),
  openapiFileInput: document.getElementById('openapi-file-input'),
  openapiDropzone: document.getElementById('openapi-dropzone'),
  openapiSpecContent: document.getElementById('openapi-spec-content'),
  btnSubmitOpenApiImport: document.getElementById('btn-submit-openapi-import'),
  optOpenApiSuccessOnly: document.getElementById('opt-openapi-success-only'),
  optOpenApiMatchHeaders: document.getElementById('opt-openapi-match-headers'),
  optOpenApiMatchQueries: document.getElementById('opt-openapi-match-queries'),
  openapiImportFeedback: document.getElementById('openapi-import-feedback'),

  // Journal Tab
  journalList: document.getElementById('journal-list'),
  journalSearch: document.getElementById('journal-search'),
  journalAutoRefresh: document.getElementById('journal-auto-refresh'),
  journalFilteredCount: document.getElementById('journal-filtered-count'),
  filterUnmatchedOnly: document.getElementById('filter-unmatched-only'),
  btnRefreshJournal: document.getElementById('btn-refresh-journal'),
  btnResetJournal: document.getElementById('btn-reset-journal'),

  // Tester Tab
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
  testerHistoryList: document.getElementById('tester-history-list'),
  testerHistoryCount: document.getElementById('tester-history-count'),
  btnTesterClearHistory: document.getElementById('btn-tester-clear-history'),
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
