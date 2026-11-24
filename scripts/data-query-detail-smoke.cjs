const fs = require('fs')
const vm = require('vm')
const assert = require('assert/strict')
const path = require('path')
const frontend = path.resolve(__dirname, '../frontend')
const compiler = require(frontend + '/node_modules/vue-template-compiler')
const parsed = compiler.parseComponent(fs.readFileSync(frontend + '/src/views/ontology/data-query/index.vue', 'utf8'))
assert.deepEqual(compiler.compile(parsed.template.content).errors, [])
const requests = []
const context = {
  module: {exports: {}},
  drilldownDataQuery: async (id, plan, sessionId, question) => {
    requests.push({id, plan, sessionId, question})
    return {data: {type: 'result', plan, rows: [], offset: plan.offset, sessionId, question}}
  }
}
vm.runInNewContext(parsed.script.content.replace(/^import .*$/gm, '').replace('export default', 'module.exports ='), context)
const component = context.module.exports
const instance = {
  catalog: {objects: [{id: 'users', name: '用户信息表', table: 'sys_user', fields: [{id: 'name', name: '用户名称', column: 'user_name', dataType: 'VARCHAR'}]}]},
  inputText: '', selectedProposalId: 7, sessionId: 'session-1', messages: [],
  $nextTick: () => {}, focusComposer: () => {}, scrollToLatestMessage: () => {},
  rememberResponse: () => {}, $modal: {msgError: message => {throw new Error(message)}}
}
for (const [key, method] of Object.entries(component.methods)) instance[key] = method.bind(instance)
instance.rememberResponse = () => {}
instance.catalogSourceText = item => item.table || ''
async function main() {
  const tree = component.computed.catalogTreeData.call(instance)
  const table = tree[0].children[0]
  assert.equal(table.label, '用户信息表（sys_user）')
  assert.equal(table.children[0].label, '用户名称 · VARCHAR')
  instance.onCatalogNodeClick(table)
  assert.equal(instance.inputText, '查看sys_user的数据')
  assert.equal(instance.filterTreeNode('sys_user', table), true)
  instance.onCatalogNodeClick(table.children[0])
  assert.equal(instance.inputText, '查看sys_user的用户名称')
  const msg = {plan: {detailObjectId: 'users', filters: [{propertyId: 'name', operator: '=', value: 'alice'}]}, offset: 0, limit: 100}
  instance.loadDetailPage(msg, 1)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(requests[0].plan.offset, 100)
  assert.equal(requests[0].plan.filters[0].value, 'alice')
  assert.equal(requests[0].sessionId, 'session-1')
  assert.equal(msg.plan.offset, undefined)
  assert.equal(instance.messages[0].viewMode, 'table')
  assert.equal(instance.sending, false)
  instance.catalog.exampleQuestions = ['查看sys_user的用户名称']
  instance.readiness = {queryEnabled: false}
  assert.equal(component.computed.exampleQuestions.call(instance).length, 0)
  instance.readiness.queryEnabled = true
  const examples = component.computed.exampleQuestions.call(instance)
  assert.equal(examples.length, 1)
  instance.startQueryTimer = instance.stopQueryTimer = () => {}
  const asked = []
  for (const data of [{}, {type: 'result'}, {type: 'clarification', clarification: ''}]) {
    context.askDataQuery = async (id, question) => {
      asked.push([id, question])
      return {data}
    }
    instance.sendQuestion(examples[0])
    await new Promise(resolve => setImmediate(resolve))
    const reply = instance.messages[instance.messages.length - 1]
    assert.equal(reply.type, 'error')
    assert.equal(reply.error, '未收到有效的查询响应，请重试')
  }
  assert.deepEqual(asked, Array(3).fill([instance.selectedProposalId, examples[0]]))
  console.log('PASS: detail catalogue, physical table selection, field selection, pagination request and conversation result')
}
main().catch(error => {console.error(error); process.exitCode = 1})
