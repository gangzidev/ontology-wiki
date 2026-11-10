<template>
  <main class="login-shell">
    <section class="login-visual" aria-label="产品介绍">
      <div class="visual-grid"></div>
      <div class="brand-mark"><span class="brand-dot"></span><span>本体 Wiki</span></div>
      <div class="visual-copy">
        <p class="eyebrow">ONTOLOGY KNOWLEDGE WORKSPACE</p>
        <h1>让数据结构<br><em>长出业务语义。</em></h1>
        <p class="visual-description">沉淀行业知识，分析数据库结构，协同生成可验证的本体配置。</p>
        <div class="visual-points">
          <span><i>01</i>知识资产沉淀</span>
          <span><i>02</i>数据库结构分析</span>
          <span><i>03</i>本体配置协作</span>
        </div>
      </div>
      <div class="visual-footer"><span>ONTOLOGY WIKI</span><span>BUILD WITH EVIDENCE</span></div>
    </section>
    <section class="login-panel">
      <div class="login-panel-inner">
        <div class="mobile-brand"><span class="brand-dot"></span>本体 Wiki</div>
        <div class="login-heading">
          <p class="eyebrow">欢迎回来</p>
          <h2>登录工作台</h2>
          <p>继续管理你的知识资产与本体分析任务。</p>
        </div>
        <el-form ref="loginForm" :model="loginForm" :rules="loginRules" class="login-form">
          <el-form-item prop="username">
            <label>账号</label>
            <el-input v-model="loginForm.username" type="text" auto-complete="off" placeholder="请输入账号">
              <svg-icon slot="prefix" icon-class="user" class="el-input__icon input-icon" />
            </el-input>
          </el-form-item>
          <el-form-item prop="password">
            <label>密码</label>
            <el-input v-model="loginForm.password" type="password" auto-complete="off" placeholder="请输入密码" @keyup.enter.native="handleLogin">
              <svg-icon slot="prefix" icon-class="password" class="el-input__icon input-icon" />
            </el-input>
          </el-form-item>
          <el-form-item v-if="captchaEnabled" prop="code">
            <label>验证码</label>
            <div class="captcha-row">
              <el-input v-model="loginForm.code" auto-complete="off" placeholder="输入验证码" @keyup.enter.native="handleLogin">
                <svg-icon slot="prefix" icon-class="validCode" class="el-input__icon input-icon" />
              </el-input>
              <img :src="codeUrl" @click="getCode" class="login-code-img" alt="验证码，点击刷新" />
            </div>
          </el-form-item>
          <div class="form-options">
            <el-checkbox v-model="loginForm.rememberMe">记住登录状态</el-checkbox>
            <span class="secure-note"><i class="el-icon-lock"></i> 安全连接</span>
          </div>
          <el-button :loading="loading" class="login-button" type="primary" @click.native.prevent="handleLogin">
            <span v-if="!loading">进入工作台 <i class="el-icon-right"></i></span>
            <span v-else>正在验证...</span>
          </el-button>
        </el-form>
        <p class="login-help">如遇到登录问题，请联系系统管理员。</p>
      </div>
      <div class="login-copyright">{{ footerContent }}</div>
    </section>
  </main>
</template>

<script>
import { getCodeImg } from "@/api/login"
import Cookies from "js-cookie"
import { encrypt, decrypt } from '@/utils/jsencrypt'
import defaultSettings from '@/settings'

export default {
  name: "Login",
  data() {
    return {
      title: process.env.VUE_APP_TITLE,
      footerContent: defaultSettings.footerContent,
      codeUrl: "",
      loginForm: {
        username: "",
        password: "",
        rememberMe: false,
        code: "",
        uuid: ""
      },
      loginRules: {
        username: [
          { required: true, trigger: "blur", message: "请输入您的账号" }
        ],
        password: [
          { required: true, trigger: "blur", message: "请输入您的密码" }
        ],
        code: [{ required: true, trigger: "change", message: "请输入验证码" }]
      },
      loading: false,
      // 验证码开关
      captchaEnabled: true,
      // 注册开关
      register: false,
      redirect: undefined
    }
  },
  watch: {
    $route: {
      handler: function(route) {
        this.redirect = route.query && route.query.redirect
      },
      immediate: true
    }
  },
  created() {
    this.getCode()
    this.getCookie()
  },
  methods: {
    getCode() {
      getCodeImg().then(res => {
        this.captchaEnabled = res.captchaEnabled === undefined ? true : res.captchaEnabled
        if (this.captchaEnabled) {
          this.codeUrl = "data:image/jpeg;base64," + res.img
          this.loginForm.uuid = res.uuid
        }
      })
    },
    getCookie() {
      const username = Cookies.get("username")
      const password = Cookies.get("password")
      const rememberMe = Cookies.get('rememberMe')
      this.loginForm = Object.assign({}, this.loginForm, {
        username: username === undefined ? this.loginForm.username : username,
        password: password === undefined ? this.loginForm.password : decrypt(password),
        rememberMe: rememberMe === 'true'
      })
    },
    handleLogin() {
      this.$refs.loginForm.validate(valid => {
        if (valid) {
          this.loading = true
          if (this.loginForm.rememberMe) {
            Cookies.set("username", this.loginForm.username, { expires: 30 })
            Cookies.set("password", encrypt(this.loginForm.password), { expires: 30 })
            Cookies.set('rememberMe', this.loginForm.rememberMe, { expires: 30 })
          } else {
            Cookies.remove("username")
            Cookies.remove("password")
            Cookies.remove('rememberMe')
          }
          this.$store.dispatch("Login", this.loginForm).then(() => {
            this.$router.push({ path: this.redirect || "/" }).catch(()=>{})
          }).catch(() => {
            this.loading = false
            if (this.captchaEnabled) {
              this.getCode()
            }
          })
        }
      })
    }
  }
}
</script>

<style rel="stylesheet/scss" lang="scss" scoped>
.login-shell {
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(520px, 1.08fr) minmax(420px, .92fr);
  background: #f6f5f4;
  color: #1d2330;
}
.login-visual {
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-height: 100vh;
  padding: 42px 7vw 34px;
  color: #fff;
  background: linear-gradient(145deg, #172335 0%, #1e314b 58%, #172335 100%);
}
.visual-grid {
  position: absolute;
  inset: 0;
  opacity: .34;
  background-image: linear-gradient(rgba(120,181,255,.1) 1px, transparent 1px), linear-gradient(90deg, rgba(120,181,255,.1) 1px, transparent 1px);
  background-size: 64px 64px;
  mask-image: linear-gradient(to bottom right, #000 8%, transparent 78%);
}
.login-visual::after {
  content: '';
  position: absolute;
  width: 620px;
  height: 620px;
  right: -250px;
  bottom: -250px;
  border-radius: 50%;
  border: 1px solid rgba(120,181,255,.42);
  box-shadow: 0 0 0 48px rgba(47,136,255,.1), 0 0 0 96px rgba(47,136,255,.05);
}
.brand-mark, .mobile-brand { position: relative; z-index: 1; display: flex; align-items: center; gap: 10px; font-size: 17px; font-weight: 700; letter-spacing: -.3px; }
.brand-dot { width: 11px; height: 11px; border-radius: 3px; display: inline-block; background: #2f88ff; box-shadow: 0 0 0 5px rgba(47,136,255,.18); }
.visual-copy { position: relative; z-index: 1; max-width: 600px; margin: auto 0; }
.eyebrow { margin: 0 0 16px; color: #0075de; font-size: 11px; font-weight: 700; letter-spacing: .14em; text-transform: uppercase; }
.visual-copy .eyebrow { color: #78b5ff; }
.visual-copy h1 { margin: 0; font-size: clamp(42px, 5vw, 70px); line-height: 1.04; letter-spacing: -2.8px; font-weight: 700; }
.visual-copy h1 em { color: #78b5ff; font-style: normal; }
.visual-description { max-width: 440px; margin: 26px 0 0; color: rgba(255,255,255,.68); font-size: 16px; line-height: 1.7; }
.visual-points { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 42px; color: rgba(255,255,255,.76); font-size: 12px; }
.visual-points span { display: flex; align-items: center; gap: 8px; padding: 8px 11px; border: 1px solid rgba(255,255,255,.13); border-radius: 999px; background: rgba(255,255,255,.05); }
.visual-points i { color: #78b5ff; font-size: 10px; font-style: normal; font-weight: 700; }
.visual-footer { position: relative; z-index: 1; display: flex; justify-content: space-between; color: rgba(255,255,255,.38); font-size: 10px; letter-spacing: .14em; }
.login-panel { position: relative; display: flex; align-items: center; justify-content: center; padding: 48px 7vw 72px; background: #f6f5f4; }
.login-panel-inner { width: 100%; max-width: 420px; padding: 42px 40px 36px; border: 1px solid rgba(0,0,0,.09); border-radius: 16px; background: #fff; box-shadow: rgba(0,0,0,.04) 0 4px 18px, rgba(0,0,0,.027) 0 2px 8px; }
.mobile-brand { display: none; color: #1d2330; margin-bottom: 52px; }
.login-heading { margin-bottom: 32px; }
.login-heading h2 { margin: 0 0 10px; color: #1d2330; font-size: 32px; line-height: 1.15; letter-spacing: -1.2px; }
.login-heading p:last-child { margin: 0; color: #64748b; font-size: 13px; line-height: 1.6; }
.login-form label { display: block; margin-bottom: 8px; color: #415064; font-size: 12px; font-weight: 600; }
.login-form .el-form-item { margin-bottom: 20px; }
.login-form ::v-deep .el-input__inner { height: 46px; padding-left: 42px; border-color: #dce3ec; border-radius: 8px; background: #fff; color: #1d2330; }
.login-form ::v-deep .el-input__inner:hover { border-color: #b9c8da; }
.login-form ::v-deep .el-input__inner:focus { border-color: #2f88ff; box-shadow: 0 0 0 3px rgba(47,136,255,.12); }
.login-form ::v-deep .el-input__inner::placeholder { color: #9aa8b8; }
.login-form ::v-deep .input-icon { color: #9aa8b8; margin-left: 4px; }
.captcha-row { display: flex; align-items: stretch; gap: 10px; }
.captcha-row .el-input { flex: 1; }
.login-code-img { width: 112px; height: 46px; object-fit: cover; border: 1px solid #dce3ec; border-radius: 8px; cursor: pointer; }
.form-options { display: flex; align-items: center; justify-content: space-between; margin: 2px 0 24px; color: #64748b; font-size: 12px; }
.form-options ::v-deep .el-checkbox__label { color: #64748b; font-size: 12px; }
.secure-note { color: #2a9d99; font-size: 12px; }
.login-button { width: 100%; height: 46px; border: 0; border-radius: 8px; background: #0075de; font-size: 14px; letter-spacing: .01em; box-shadow: 0 6px 14px rgba(0,117,222,.2); }
.login-button:hover, .login-button:focus { background: #005bab; }
.login-button i { margin-left: 6px; }
.login-help { margin: 24px 0 0; color: #9aa8b8; font-size: 11px; text-align: center; }
.login-copyright { position: absolute; right: 32px; bottom: 22px; left: 32px; color: #9aa8b8; font-size: 11px; text-align: center; }
@media (max-width: 900px) {
  .login-shell { display: block; }
  .login-visual { display: none; }
  .login-panel { min-height: 100vh; padding: 42px 28px 70px; }
  .login-panel-inner { max-width: 420px; }
  .mobile-brand { display: flex; margin-bottom: 52px; }
}
@media (max-width: 480px) {
  .login-panel { padding: 28px 20px 64px; }
  .login-panel-inner { padding: 32px 24px 28px; border-radius: 14px; }
  .mobile-brand { margin-bottom: 42px; }
  .login-heading h2 { font-size: 30px; }
  .login-copyright { right: 20px; left: 20px; }
}
</style>
