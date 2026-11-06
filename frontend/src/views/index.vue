<template>
  <div class="app-container overview-page">
    <section class="overview-hero">
      <div class="hero-copy">
        <p class="eyebrow">ONTOLOGY WIKI</p>
        <h1>让数据库结构，长出业务语义。</h1>
        <p>以 Wiki 沉淀行业知识，结合 Hive、MySQL、PostgreSQL 结构分析，协同生成可审核的本体配置。</p>
        <div class="hero-actions">
          <el-button type="primary" icon="el-icon-s-grid" @click="go('/knowledge/dashboard')">进入工作台</el-button>
          <el-button icon="el-icon-chat-line-round" @click="go('/knowledge/chat')">业务知识问答</el-button>
        </div>
      </div>
      <div class="hero-graphic" aria-hidden="true">
        <div class="graphic-node primary">业务语义</div>
        <div class="graphic-node node-left">行业知识</div>
        <div class="graphic-node node-right">数据结构</div>
        <div class="graphic-line line-left"></div>
        <div class="graphic-line line-right"></div>
      </div>
    </section>

    <section class="capabilities">
      <div class="section-heading"><div><h2>平台能力</h2><p>从知识理解到配置交付，关键决策保留人工确认。</p></div></div>
      <el-row :gutter="16">
        <el-col v-for="item in capabilities" :key="item.title" :xs="24" :sm="8">
          <el-card shadow="never" class="capability-card" @click.native="go(item.path)">
            <div class="capability-icon" :class="item.tone"><i :class="item.icon"></i></div>
            <h3>{{ item.title }}</h3>
            <p>{{ item.description }}</p>
            <span class="card-link">{{ item.action }} <i class="el-icon-arrow-right"></i></span>
          </el-card>
        </el-col>
      </el-row>
    </section>

    <div class="review-note"><i class="el-icon-circle-check"></i><span>生成内容作为候选配置，由工程师审核并在本体平台测试后再导入。</span></div>
  </div>
</template>

<script>
export default {
  name: 'Overview',
  data: () => ({
    capabilities: [
      { title: '业务知识问答', description: '按行业知识空间询问业务术语、规则和流程，回答附带资料依据。', action: '查业务知识', path: '/knowledge/chat', icon: 'el-icon-chat-line-round', tone: 'blue' },
      { title: '数据库结构分析', description: '连接业务数据库，梳理表、字段、注释与局部样例。', action: '管理数据源', path: '/engineering/datasource', icon: 'el-icon-coin', tone: 'green' },
      { title: '本体配置协作', description: '整理对象、逻辑和动作候选，审阅后交由本体平台验证。', action: '查看配置草案', path: '/engineering/proposal', icon: 'el-icon-document-checked', tone: 'purple' }
    ]
  }),
  methods: { go(path) { this.$router.push(path) } }
}
</script>

<style scoped>
.overview-page { max-width: 1440px; margin: 0 auto; }
.overview-hero { display:flex; align-items:center; justify-content:space-between; min-height:260px; padding:38px 42px; margin-bottom:28px; border:1px solid rgba(0,0,0,.08); border-radius:16px; background:linear-gradient(115deg,#fff 0%,#fff 58%,#f2f8ff 100%); box-shadow:rgba(0,0,0,.04) 0 4px 18px; }
.hero-copy { max-width:700px; }.eyebrow { margin:0 0 14px; color:#0075de; font-size:11px; font-weight:700; letter-spacing:.16em; }.overview-hero h1 { margin:0; color:#252b34; font-size:34px; letter-spacing:-1px; line-height:1.25; }.overview-hero p:not(.eyebrow) { max-width:620px; margin:14px 0 0; color:#68717d; font-size:15px; line-height:1.8; }.hero-actions { display:flex; gap:10px; margin-top:24px; }
.hero-graphic { position:relative; width:250px; height:180px; margin:0 18px; flex:none; }.graphic-node { position:absolute; z-index:1; display:flex; align-items:center; justify-content:center; width:104px; height:54px; border:1px solid #dce9f8; border-radius:13px; background:#fff; color:#485563; font-size:13px; font-weight:600; box-shadow:0 8px 24px rgba(30,69,115,.08); }.graphic-node.primary { top:64px; left:72px; width:118px; height:62px; border-color:#2f88ff; background:#2f88ff; color:#fff; box-shadow:0 10px 26px rgba(47,136,255,.24); }.node-left { top:5px; left:0; }.node-right { right:0; bottom:3px; }.graphic-line { position:absolute; height:1px; background:#b9d2ef; transform-origin:left center; }.line-left { top:48px; left:82px; width:94px; transform:rotate(24deg); }.line-right { top:116px; left:166px; width:90px; transform:rotate(32deg); }
.capabilities { margin-top:0; }.section-heading { margin:0 0 16px; }.section-heading h2 { margin:0; color:#31302e; font-size:20px; }.section-heading p { margin:7px 0 0; color:#8a8580; font-size:13px; }.capability-card { min-height:210px; border-radius:12px; cursor:pointer; transition:transform .18s ease, box-shadow .18s ease; }.capability-card:hover { transform:translateY(-2px); box-shadow:0 8px 22px rgba(24,45,72,.08); }.capability-icon { display:flex; align-items:center; justify-content:center; width:38px; height:38px; border-radius:11px; font-size:19px; }.capability-icon.blue { color:#0075de; background:#f2f9ff; }.capability-icon.green { color:#258c78; background:#edf8f3; }.capability-icon.purple { color:#6a48a8; background:#f4effb; }.capability-card h3 { margin:18px 0 8px; color:#31302e; font-size:16px; }.capability-card p { min-height:42px; margin:0; color:#77716b; font-size:13px; line-height:1.65; }.card-link { display:inline-block; margin-top:16px; color:#0075de; font-size:13px; font-weight:600; }.review-note { display:flex; align-items:center; gap:9px; padding:15px 18px; margin-top:18px; border:1px solid #dceee8; border-radius:10px; background:#f7fbf9; color:#5b7168; font-size:13px; }.review-note i { color:#2a9d99; font-size:16px; }
@media (max-width: 900px) { .hero-graphic { transform:scale(.82); transform-origin:right center; margin-right:0; } }
@media (max-width: 680px) { .overview-hero { display:block; padding:28px 24px; }.overview-hero h1 { font-size:27px; }.hero-graphic { display:none; }.hero-actions { flex-wrap:wrap; } }
</style>
