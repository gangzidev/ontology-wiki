const fs = require('fs')
const vm = require('vm')
const assert = require('assert/strict')
const frontend = require('path').resolve(__dirname, '../frontend')
const compiler = require(frontend + '/node_modules/vue-template-compiler')
const component = compiler.parseComponent(fs.readFileSync(frontend + '/src/views/ontology/resource/index.vue', 'utf8'))
for (const name of ['src/layout/components/AppMain.vue', 'src/views/ontology/chat/index.vue', 'src/views/ontology/data-query/index.vue', 'src/views/ontology/resource/index.vue']) {
  const parsed = compiler.parseComponent(fs.readFileSync(frontend + '/' + name, 'utf8'))
  assert.deepEqual(compiler.compile(parsed.template.content).errors, [], name)
}
let job = {status: 'RUNNING', message: '3/4 补全中文', resumable: false}
let failure = null
let timers = []
let cancelled = []
let resumed = []
const context = {
  module: {exports:{}}, OntologyQueryEvaluation: {},
  setTimeout: callback => {timers.push(callback); return timers.length},
  clearTimeout: () => {}, Date,
  getAnalysisJob: async () => {if (failure) throw failure; return {data:job}},
  getLatestAnalysisResult: async () => ({data:{proposalId:8,proposalConfig:{objects:[{name:'中文对象'}]}}}),
  cancelAnalysisJob: async taskId => {cancelled.push(taskId);return {data:{status:'CANCELLED',resumable:true,message:'已取消'}}},
  resumeAnalysisJob: async taskId => {resumed.push(taskId);return {data:{async:true}}}
}
vm.runInNewContext(component.script.content.replace(/^import .*$/gm, '').replace('export default', 'module.exports ='), context)
const methods=context.module.exports.methods
const instance = {$modal:{confirm:async()=>true},load:()=>{},trackAction:()=>{},analysisProgress:{},analysisPollId:0}
for (const key of ['startAnalysisProgress','stopAnalysisProgress','addAnalysisProgressLog','analysisStatusText','cancelCurrentAnalysis','resumeCurrentAnalysis']) instance[key]=methods[key].bind(instance)
const flush=()=>new Promise(resolve=>setImmediate(resolve))
async function main(){
  instance.startAnalysisProgress(1,'订单分析')
  await timers.shift()()
  assert.equal(instance.analysisProgress.step,3)
  job={status:'INTERRUPTED',message:'执行中断',resumable:true}
  await timers.shift()()
  assert.equal(instance.analysisProgress.status,'INTERRUPTED')
  assert.equal(instance.analysisProgress.resumable,true)
  instance.resumeCurrentAnalysis();await flush()
  assert.deepEqual(resumed,[1])
  job={status:'RUNNING',message:'3/4 正在分析'}
  instance.cancelCurrentAnalysis();await flush()
  assert.deepEqual(cancelled,[1]);assert.equal(instance.analysisProgress.status,'CANCELLED')
  timers=[];instance.startAnalysisProgress(2,'新任务')
  job={status:'DONE',message:'4/4 已完成'}
  await timers.shift()()
  assert.equal(instance.analysisResult.proposalId,8)
  assert.equal(instance.analysisConfig.objects[0].name,'中文对象')
  timers=[];instance.startAnalysisProgress(3,'失败任务')
  failure=new Error('任务存储未就绪')
  await timers.shift()()
  assert.equal(instance.analysisProgress.status,'FAILED')
  assert.equal(instance.analysisProgress.error,'任务存储未就绪')
  assert.equal(timers.length,0)
  failure=null;timers=[];instance.startAnalysisProgress(4,'旧任务')
  const stale=timers.shift()
  instance.startAnalysisProgress(5,'新任务')
  await stale()
  assert.equal(instance.analysisProgress.taskId,5)
  // Responses already in flight must not change a newly opened task.
  timers=[];instance.startAnalysisProgress(6,'旧进度请求')
  let resolveProgress
  context.getAnalysisJob = () => new Promise(resolve => {resolveProgress=resolve})
  const pendingProgress = timers.shift()()
  instance.startAnalysisProgress(7,'新进度请求')
  const progressPollId = instance.analysisPollId
  resolveProgress({data:{status:'FAILED',message:'旧任务失败'}})
  await pendingProgress
  assert.equal(instance.analysisProgress.taskId,7)
  assert.equal(instance.analysisProgress.status,'RUNNING')
  assert.equal(instance.analysisPollId,progressPollId)

  context.getAnalysisJob = async () => ({data:{status:'DONE',message:'4/4 已完成'}})
  for (const rejectResult of [false,true]) {
    timers=[];instance.startAnalysisProgress(8,'旧结果请求')
    let resolveResult, failResult
    context.getLatestAnalysisResult = () => new Promise((resolve,reject) => {resolveResult=resolve;failResult=reject})
    const pendingResult = timers.shift()()
    await flush()
    instance.startAnalysisProgress(9,'新任务')
    const resultPollId = instance.analysisPollId
    const resultMessage = instance.analysisProgress.message
    if (rejectResult) failResult(new Error('旧结果读取失败'))
    else resolveResult({data:{proposalId:99,proposalConfig:{objects:[{name:'旧结果'}]}}})
    await pendingResult
    assert.equal(instance.analysisProgress.taskId,9)
    assert.equal(instance.analysisProgress.status,'RUNNING')
    assert.equal(instance.analysisProgress.message,resultMessage)
    assert.equal(instance.analysisPollId,resultPollId)
    assert.equal(instance.analysisResult.proposalId,undefined)
  }
  console.log('PASS: template, progress, interruption, cancellation, resume, completion, failure, stale timer/progress/result isolation')
}
main().catch(error=>{console.error(error);process.exitCode=1})
