<script>
const menuIconMap = {
  '本体Wiki': 'el-icon-connection',
  '首页': 'el-icon-s-home',
  '工作台': 'el-icon-s-grid',
  'Wiki资产': 'el-icon-notebook-2',
  '数据源管理': 'el-icon-coin',
  '分析任务': 'el-icon-data-analysis',
  '配置草案': 'el-icon-document-checked',
  'Wiki空间': 'el-icon-folder-opened',
  '行业知识空间': 'el-icon-folder-opened',
  '业务知识问答': 'el-icon-chat-line-round'
}

export default {
  name: 'MenuItem',
  functional: true,
  props: {
    icon: {
      type: String,
      default: ''
    },
    title: {
      type: String,
      default: ''
    }
  },
  render(h, context) {
    const { icon, title } = context.props
    const menuIcon = menuIconMap[title] || icon
    const vnodes = []

    if (menuIcon) {
      vnodes.push(menuIcon.indexOf('el-icon-') === 0
        ? <i class={'menu-entry-icon ' + menuIcon}/>
        : <svg-icon icon-class={menuIcon}/>)
    }

    if (title) {
      if (title.length > 5) {
        vnodes.push(<span slot='title' title={(title)}>{(title)}</span>)
      } else {
        vnodes.push(<span slot='title'>{(title)}</span>)
      }
    }
    return vnodes
  }
}
</script>
