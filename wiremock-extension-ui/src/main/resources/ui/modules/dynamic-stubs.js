/**
 * Dynamic / Stateful Simulation Engine UI Module.
 * Integrates dynamic models with standard stubs, provides creation templates,
 * and handles stateful inspection.
 */
import { state } from './state.js';
import { elements, escapeHtml } from './dom.js';
import { createStatefulModel, loadData } from './api.js';

export const DYNAMIC_TEMPLATES = {
  'case-management': {
    name: 'Case Management (Cases & Tasks)',
    entity: 'cases',
    idPathParam: 'caseId',
    idPrefix: 'case-',
    basePath: '/api/v1/cases',
    model: {
      entity: 'cases',
      idPathParam: 'caseId',
      idPrefix: 'case-',
      rules: [
        {
          route: { method: 'POST', pathPattern: '/api/v1/cases' },
          invariants: [],
          mutations: [
            {
              type: 'init_entity',
              idParam: 'caseId',
              defaultFields: { status: 'OPEN', tasks: [] }
            }
          ],
          response: {
            status: 201,
            source: { type: 'entity' },
            headers: { 'Content-Type': 'application/json' }
          }
        },
        {
          route: { method: 'GET', pathPattern: '/api/v1/cases/{caseId}' },
          invariants: [],
          mutations: [],
          response: {
            status: 200,
            source: { type: 'entity' },
            headers: { 'Content-Type': 'application/json' }
          }
        },
        {
          route: { method: 'POST', pathPattern: '/api/v1/cases/{caseId}/tasks' },
          invariants: [],
          mutations: [
            {
              type: 'append_to_list',
              collectionPath: 'tasks',
              item: { type: 'request_body_with_id', idField: 'id', idPrefix: 'task-' }
            }
          ],
          response: {
            status: 201,
            source: { type: 'entity' },
            headers: { 'Content-Type': 'application/json' }
          }
        },
        {
          route: { method: 'PUT', pathPattern: '/api/v1/cases/{caseId}/close' },
          invariants: [
            {
              condition: {
                type: 'collection_all_match',
                collectionPath: 'tasks',
                predicate: {
                  type: 'binary_op',
                  operator: 'NOT_EQUALS',
                  left: { type: 'field_ref', path: 'status' },
                  right: { type: 'literal', value: 'PENDING' }
                }
              },
              failStatus: 409,
              failMessage: 'Cannot close case: pending tasks exist'
            }
          ],
          mutations: [
            {
              type: 'set_field',
              path: 'status',
              value: { type: 'literal_value', value: 'CLOSED' }
            }
          ],
          response: {
            status: 200,
            source: { type: 'entity' },
            headers: { 'Content-Type': 'application/json' }
          }
        }
      ]
    }
  },
  'order-fulfillment': {
    name: 'Order Fulfillment (Orders & Items)',
    entity: 'orders',
    idPathParam: 'orderId',
    idPrefix: 'ord-',
    basePath: '/api/v1/orders',
    model: {
      entity: 'orders',
      idPathParam: 'orderId',
      idPrefix: 'ord-',
      rules: [
        {
          route: { method: 'POST', pathPattern: '/api/v1/orders' },
          invariants: [],
          mutations: [
            {
              type: 'init_entity',
              idParam: 'orderId',
              defaultFields: { status: 'CREATED', items: [] }
            }
          ],
          response: {
            status: 201,
            source: { type: 'entity' },
            headers: { 'Content-Type': 'application/json' }
          }
        },
        {
          route: { method: 'GET', pathPattern: '/api/v1/orders/{orderId}' },
          invariants: [],
          mutations: [],
          response: {
            status: 200,
            source: { type: 'entity' },
            headers: { 'Content-Type': 'application/json' }
          }
        },
        {
          route: { method: 'POST', pathPattern: '/api/v1/orders/{orderId}/items' },
          invariants: [],
          mutations: [
            {
              type: 'append_to_list',
              collectionPath: 'items',
              item: { type: 'request_body' }
            }
          ],
          response: {
            status: 201,
            source: { type: 'entity' },
            headers: { 'Content-Type': 'application/json' }
          }
        },
        {
          route: { method: 'PUT', pathPattern: '/api/v1/orders/{orderId}/ship' },
          invariants: [
            {
              condition: {
                type: 'binary_op',
                operator: 'GREATER_THAN',
                left: { type: 'collection_size', path: 'items' },
                right: { type: 'literal', value: 0 }
              },
              failStatus: 400,
              failMessage: 'cannot ship empty order'
            }
          ],
          mutations: [
            {
              type: 'set_field',
              path: 'status',
              value: { type: 'literal_value', value: 'SHIPPED' }
            }
          ],
          response: {
            status: 200,
            source: { type: 'entity' },
            headers: { 'Content-Type': 'application/json' }
          }
        }
      ]
    }
  }
};

/**
 * Normalizes stateful model rules into unified stub representations.
 */
export function normalizeStatefulModelsToStubs(models) {
  if (!Array.isArray(models)) return [];
  const result = [];

  models.forEach((model, modelIdx) => {
    const entity = model.entity || 'entity';
    const rules = Array.isArray(model.rules) ? model.rules : [];

    rules.forEach((rule, ruleIdx) => {
      const method = (rule.route && rule.route.method) || 'GET';
      const path = (rule.route && rule.route.pathPattern) || `/${entity}`;
      const statusCode = (rule.response && rule.response.statusCode) || 200;
      const id = `dynamic-${entity}-${ruleIdx}`;

      const invariants = Array.isArray(rule.invariants) ? rule.invariants : [];
      const mutations = Array.isArray(rule.mutations) ? rule.mutations : [];

      result.push({
        id: id,
        isDynamic: true,
        name: `[⚡ DYNAMIC] ${entity.toUpperCase()}: ${method} ${path}`,
        priority: 1,
        request: {
          method: method,
          urlPathTemplate: path
        },
        response: {
          status: statusCode
        },
        model: model,
        rule: rule,
        entity: entity,
        urlRange: `${path.replace(/{[^}]+}/g, '*')}`,
        invariants: invariants,
        mutations: mutations,
        metadata: {
          project: `⚡ ${entity}`,
          tags: ['dynamic', 'stateful', entity]
        }
      });
    });
  });

  return result;
}

/**
 * Initializes the dynamic stub creation modal and event listeners.
 */
export function setupDynamicStubModal() {
  const modal = document.getElementById('dynamic-stub-modal');
  const btnOpen = document.getElementById('btn-create-dynamic-stub');
  const btnClose = document.getElementById('btn-close-dynamic-modal');
  const btnCancel = document.getElementById('btn-cancel-dynamic');
  const btnSave = document.getElementById('btn-save-dynamic');
  const selectTemplate = document.getElementById('dynamic-template-select');
  const txtJson = document.getElementById('dynamic-model-json');
  const inputEntity = document.getElementById('dynamic-entity-name');
  const inputParam = document.getElementById('dynamic-id-param');
  const inputPrefix = document.getElementById('dynamic-id-prefix');

  if (!modal) return;

  function applyTemplate(key) {
    const tpl = DYNAMIC_TEMPLATES[key] || DYNAMIC_TEMPLATES['case-management'];
    if (inputEntity) inputEntity.value = tpl.entity;
    if (inputParam) inputParam.value = tpl.idPathParam;
    if (inputPrefix) inputPrefix.value = tpl.idPrefix;
    if (txtJson) txtJson.value = JSON.stringify(tpl.model, null, 2);
  }

  if (btnOpen) {
    btnOpen.addEventListener('click', () => {
      applyTemplate(selectTemplate ? selectTemplate.value : 'case-management');
      modal.classList.remove('hidden');
    });
  }

  if (btnClose) {
    btnClose.addEventListener('click', () => modal.classList.add('hidden'));
  }

  if (btnCancel) {
    btnCancel.addEventListener('click', () => modal.classList.add('hidden'));
  }

  if (selectTemplate) {
    selectTemplate.addEventListener('change', () => {
      applyTemplate(selectTemplate.value);
    });
  }

  if (btnSave) {
    btnSave.addEventListener('click', () => {
      let modelData;
      try {
        modelData = JSON.parse(txtJson ? txtJson.value : '{}');
      } catch (err) {
        alert('Invalid JSON in model definition: ' + err.message);
        return;
      }

      // Sync form overrides if edited
      if (inputEntity && inputEntity.value.trim()) modelData.entity = inputEntity.value.trim();
      if (inputParam && inputParam.value.trim()) modelData.idPathParam = inputParam.value.trim();
      if (inputPrefix && inputPrefix.value.trim()) modelData.idPrefix = inputPrefix.value.trim();

      btnSave.disabled = true;
      btnSave.textContent = 'Saving...';

      createStatefulModel(modelData)
        .then(() => {
          modal.classList.add('hidden');
          return loadData();
        })
        .catch(err => {
          alert('Failed to register dynamic model: ' + err.message);
        })
        .finally(() => {
          btnSave.disabled = false;
          btnSave.textContent = '⚡ Create Dynamic Model';
        });
    });
  }
}
