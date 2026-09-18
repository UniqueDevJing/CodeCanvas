package com.codecanvas.llm.mock;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 离线引擎的页面模板库。
 *
 * <p>作用：在没有可用模型密钥时，依然能产出"看起来真的能用"的页面，
 * 让整条链路（生成 → 预览 → 迭代 → 自检）可以被完整演示和调试。
 *
 * <p>注意：记账 / 待办 / 番茄钟 / 看板这几个模板会使用 localStorage 持久化，
 * 而预览沙箱使用的是 opaque origin，localStorage 会抛 SecurityError。
 * 这不是缺陷，而是沙箱隔离的必然结果——自检闭环正是为这类真实问题准备的。
 */
final class MockAppTemplates {

    private MockAppTemplates() {
    }

    record App(String title, String subtitle, String css, String body, String script) {
    }

    private static final String BASE_CSS = """
            *{box-sizing:border-box;margin:0;padding:0}
            :root{
              --bg:#f6f6f4;--panel:#ffffff;--text:#1a1a18;--muted:#8a8a85;
              --border:#e8e8e5;--accent:#4f46e5;--accent-soft:#eef0fe;--radius:12px;
            }
            body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;
              background:var(--bg);color:var(--text);font-size:14px;line-height:1.6;padding:24px;-webkit-font-smoothing:antialiased}
            .wrap{max-width:780px;margin:0 auto}
            h1{font-size:20px;font-weight:600;letter-spacing:-0.01em}
            h2{font-size:15px;font-weight:600}
            .muted{color:var(--muted)}
            .card{background:var(--panel);border:1px solid var(--border);border-radius:var(--radius);padding:20px}
            .row{display:flex;gap:10px;align-items:center}
            .btn{border:1px solid var(--border);background:var(--panel);color:var(--text);padding:8px 14px;
              border-radius:8px;font-size:13px;cursor:pointer;font-family:inherit}
            .btn:hover{border-color:#d4d4d0}
            .btn-primary{background:var(--accent);border-color:var(--accent);color:#fff}
            .btn-primary:hover{opacity:.92}
            .input{flex:1;border:1px solid var(--border);border-radius:8px;padding:9px 12px;font-size:13px;
              font-family:inherit;background:var(--panel);color:var(--text);outline:none}
            .input:focus{border-color:var(--accent);box-shadow:0 0 0 3px var(--accent-soft)}
            .list{list-style:none;display:flex;flex-direction:column;gap:8px}
            .item{display:flex;align-items:center;gap:10px;border:1px solid var(--border);
              border-radius:10px;padding:12px 14px;background:var(--panel)}
            .grow{flex:1}
            .badge{display:inline-block;padding:2px 9px;border-radius:999px;background:var(--accent-soft);
              color:var(--accent);font-size:12px}
            .stat{font-size:26px;font-weight:600;letter-spacing:-0.02em}
            .grid-2{display:grid;grid-template-columns:1fr 1fr;gap:12px}
            .grid-3{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}
            .stack{display:flex;flex-direction:column;gap:14px}
            .head{display:flex;justify-content:space-between;align-items:flex-start;gap:12px}
            @media(max-width:620px){body{padding:14px}.grid-2,.grid-3{grid-template-columns:1fr}}
            """;

    /** 关键词 → 模板。用 LinkedHashMap 保证匹配顺序稳定（更具体的关键词放前面）。 */
    private static final Map<String, String> ROUTES = new LinkedHashMap<>();

    static {
        ROUTES.put("记账|账本|支出|花销|花钱|预算|理财|消费", "expense");
        ROUTES.put("番茄|专注|计时器|倒计时|pomodoro", "pomodoro");
        ROUTES.put("看板|任务板|kanban|项目板|进度板", "kanban");
        ROUTES.put("待办|任务清单|清单|todo|打卡|事项", "todo");
        ROUTES.put("计算器|算数|四则运算", "calculator");
        ROUTES.put("落地页|官网|宣传|介绍页|主页|产品页|咖啡馆|餐厅", "landing");
        ROUTES.put("仪表盘|统计|数据|报表|图表|大盘|概览", "dashboard");
    }

    static String detect(String requirement) {
        if (requirement == null) {
            return "generic";
        }
        String text = requirement.toLowerCase();
        for (Map.Entry<String, String> entry : ROUTES.entrySet()) {
            for (String keyword : entry.getKey().split("\\|")) {
                if (text.contains(keyword)) {
                    return entry.getValue();
                }
            }
        }
        return "generic";
    }

    static App build(String archetype, String requirement) {
        return switch (archetype) {
            case "expense" -> expense();
            case "pomodoro" -> pomodoro();
            case "kanban" -> kanban();
            case "todo" -> todo();
            case "calculator" -> calculator();
            case "landing" -> landing(requirement);
            case "dashboard" -> dashboard();
            default -> generic(requirement);
        };
    }

    static String document(App app, String accent) {
        String css = BASE_CSS;
        if (accent != null && !accent.isBlank()) {
            css = css.replace("--accent:#4f46e5;", "--accent:" + accent + ";");
        }
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>%s</title>
                <style>
                %s
                %s
                </style>
                </head>
                <body>
                %s
                <script>
                %s
                </script>
                </body>
                </html>
                """.formatted(app.title(), css, app.css(), app.body(), app.script());
    }

    private static App expense() {
        String body = """
                <div class="wrap stack">
                  <div class="head">
                    <div>
                      <h1>记账本</h1>
                      <div class="muted">记录每一笔支出，随时看清这个月花了多少</div>
                    </div>
                    <span class="badge" id="count">0 笔</span>
                  </div>
                  <div class="card">
                    <div class="row">
                      <input class="input" id="desc" placeholder="这笔钱花在哪，例如：午餐">
                      <input class="input" id="amount" type="number" placeholder="金额" style="max-width:130px">
                      <button class="btn btn-primary" id="add">记一笔</button>
                    </div>
                  </div>
                  <div class="card row" style="justify-content:space-between">
                    <span class="muted">合计支出</span>
                    <span class="stat" id="total">¥0.00</span>
                  </div>
                  <ul class="list" id="list"></ul>
                  <div class="muted" id="empty" style="text-align:center;padding:24px 0">还没有记录，记下第一笔吧</div>
                </div>
                """;
        String script = """
                var STORE_KEY = 'cc_expense_items';
                var items = [];
                function loadItems() {
                  var raw = localStorage.getItem(STORE_KEY);
                  items = raw ? JSON.parse(raw) : [];
                }
                function saveItems() {
                  localStorage.setItem(STORE_KEY, JSON.stringify(items));
                }
                function render() {
                  var list = document.getElementById('list');
                  list.innerHTML = '';
                  var total = 0;
                  items.forEach(function (item, index) {
                    total += item.amount;
                    var li = document.createElement('li');
                    li.className = 'item';
                    var name = document.createElement('span');
                    name.className = 'grow';
                    name.textContent = item.desc;
                    var amount = document.createElement('span');
                    amount.textContent = '¥' + item.amount.toFixed(2);
                    var remove = document.createElement('button');
                    remove.className = 'btn';
                    remove.textContent = '删除';
                    remove.onclick = function () {
                      items.splice(index, 1);
                      saveItems();
                      render();
                    };
                    li.appendChild(name);
                    li.appendChild(amount);
                    li.appendChild(remove);
                    list.appendChild(li);
                  });
                  document.getElementById('total').textContent = '¥' + total.toFixed(2);
                  document.getElementById('count').textContent = items.length + ' 笔';
                  document.getElementById('empty').style.display = items.length ? 'none' : 'block';
                }
                document.getElementById('add').onclick = function () {
                  var desc = document.getElementById('desc').value.trim();
                  var amount = parseFloat(document.getElementById('amount').value);
                  if (!desc || isNaN(amount)) {
                    alert('请填写说明和金额');
                    return;
                  }
                  items.push({ desc: desc, amount: amount });
                  document.getElementById('desc').value = '';
                  document.getElementById('amount').value = '';
                  saveItems();
                  render();
                };
                loadItems();
                render();
                """;
        return new App("记账本", "记录每一笔支出", "", body, script);
    }

    private static App todo() {
        String body = """
                <div class="wrap stack">
                  <div class="head">
                    <div>
                      <h1>待办清单</h1>
                      <div class="muted">今天要完成的事，一件件划掉</div>
                    </div>
                    <span class="badge" id="progress">0 / 0</span>
                  </div>
                  <div class="card">
                    <div class="row">
                      <input class="input" id="text" placeholder="要做点什么，例如：写完项目文档">
                      <button class="btn btn-primary" id="add">添加</button>
                    </div>
                  </div>
                  <ul class="list" id="list"></ul>
                  <div class="muted" id="empty" style="text-align:center;padding:24px 0">清单是空的，添加第一件事</div>
                  <div class="row" style="justify-content:flex-end">
                    <button class="btn" id="clear">清除已完成</button>
                  </div>
                </div>
                """;
        String script = """
                var STORE_KEY = 'cc_todo_items';
                var todos = [];
                function loadTodos() {
                  var raw = localStorage.getItem(STORE_KEY);
                  todos = raw ? JSON.parse(raw) : [];
                }
                function saveTodos() {
                  localStorage.setItem(STORE_KEY, JSON.stringify(todos));
                }
                function render() {
                  var list = document.getElementById('list');
                  list.innerHTML = '';
                  var done = 0;
                  todos.forEach(function (todo, index) {
                    if (todo.done) { done++; }
                    var li = document.createElement('li');
                    li.className = 'item';
                    var box = document.createElement('input');
                    box.type = 'checkbox';
                    box.checked = todo.done;
                    box.onchange = function () {
                      todos[index].done = box.checked;
                      saveTodos();
                      render();
                    };
                    var text = document.createElement('span');
                    text.className = 'grow';
                    text.textContent = todo.text;
                    if (todo.done) { text.style.textDecoration = 'line-through'; text.style.color = '#8a8a85'; }
                    var remove = document.createElement('button');
                    remove.className = 'btn';
                    remove.textContent = '删除';
                    remove.onclick = function () {
                      todos.splice(index, 1);
                      saveTodos();
                      render();
                    };
                    li.appendChild(box);
                    li.appendChild(text);
                    li.appendChild(remove);
                    list.appendChild(li);
                  });
                  document.getElementById('progress').textContent = done + ' / ' + todos.length;
                  document.getElementById('empty').style.display = todos.length ? 'none' : 'block';
                }
                document.getElementById('add').onclick = function () {
                  var text = document.getElementById('text').value.trim();
                  if (!text) { return; }
                  todos.push({ text: text, done: false });
                  document.getElementById('text').value = '';
                  saveTodos();
                  render();
                };
                document.getElementById('clear').onclick = function () {
                  todos = todos.filter(function (todo) { return !todo.done; });
                  saveTodos();
                  render();
                };
                loadTodos();
                render();
                """;
        return new App("待办清单", "一件件划掉", "", body, script);
    }

    private static App pomodoro() {
        String body = """
                <div class="wrap stack" style="max-width:460px">
                  <div class="head">
                    <div>
                      <h1>番茄钟</h1>
                      <div class="muted">专注 25 分钟，休息 5 分钟</div>
                    </div>
                    <span class="badge" id="rounds">已完成 0 轮</span>
                  </div>
                  <div class="card" style="text-align:center;padding:36px 20px">
                    <div style="font-size:56px;font-weight:600;letter-spacing:-0.03em;font-variant-numeric:tabular-nums" id="clock">25:00</div>
                    <div class="muted" id="phase" style="margin-top:6px">专注时间</div>
                    <div class="row" style="justify-content:center;margin-top:22px">
                      <button class="btn btn-primary" id="toggle">开始</button>
                      <button class="btn" id="reset">重置</button>
                    </div>
                  </div>
                  <div class="card">
                    <div class="row" style="justify-content:space-between">
                      <span class="muted">专注时长（分钟）</span>
                      <input class="input" id="minutes" type="number" value="25" style="max-width:90px">
                    </div>
                  </div>
                </div>
                """;
        String script = """
                var STORE_KEY = 'cc_pomodoro_rounds';
                var remaining = 25 * 60;
                var total = 25 * 60;
                var running = false;
                var timer = null;
                var rounds = 0;
                function loadRounds() {
                  rounds = parseInt(localStorage.getItem(STORE_KEY) || '0', 10);
                }
                function saveRounds() {
                  localStorage.setItem(STORE_KEY, String(rounds));
                }
                function paint() {
                  var minutes = Math.floor(remaining / 60);
                  var seconds = remaining % 60;
                  document.getElementById('clock').textContent =
                    (minutes < 10 ? '0' : '') + minutes + ':' + (seconds < 10 ? '0' : '') + seconds;
                  document.getElementById('rounds').textContent = '已完成 ' + rounds + ' 轮';
                }
                function tick() {
                  if (remaining > 0) {
                    remaining--;
                    paint();
                    return;
                  }
                  running = false;
                  clearInterval(timer);
                  document.getElementById('toggle').textContent = '开始';
                  document.getElementById('phase').textContent = '本轮完成，休息一下';
                  rounds++;
                  saveRounds();
                  paint();
                }
                document.getElementById('toggle').onclick = function () {
                  if (running) {
                    clearInterval(timer);
                    running = false;
                    this.textContent = '继续';
                    document.getElementById('phase').textContent = '已暂停';
                    return;
                  }
                  running = true;
                  this.textContent = '暂停';
                  document.getElementById('phase').textContent = '专注时间';
                  timer = setInterval(tick, 1000);
                };
                document.getElementById('reset').onclick = function () {
                  clearInterval(timer);
                  running = false;
                  total = Math.max(1, parseInt(document.getElementById('minutes').value, 10) || 25) * 60;
                  remaining = total;
                  document.getElementById('toggle').textContent = '开始';
                  document.getElementById('phase').textContent = '专注时间';
                  paint();
                };
                loadRounds();
                paint();
                """;
        return new App("番茄钟", "专注计时", "", body, script);
    }

    private static App kanban() {
        String body = """
                <div class="wrap stack" style="max-width:960px">
                  <div class="head">
                    <div>
                      <h1>任务看板</h1>
                      <div class="muted">点击卡片把它推进到下一列</div>
                    </div>
                    <button class="btn btn-primary" id="add">新建任务</button>
                  </div>
                  <div class="grid-3" id="board"></div>
                </div>
                """;
        String script = """
                var STORE_KEY = 'cc_kanban_cards';
                var COLUMNS = ['待开始', '进行中', '已完成'];
                var cards = [];
                function loadCards() {
                  var raw = localStorage.getItem(STORE_KEY);
                  cards = raw ? JSON.parse(raw) : [
                    { title: '梳理需求清单', column: 0 },
                    { title: '搭建页面骨架', column: 1 },
                    { title: '确定配色方案', column: 2 }
                  ];
                }
                function saveCards() {
                  localStorage.setItem(STORE_KEY, JSON.stringify(cards));
                }
                function render() {
                  var board = document.getElementById('board');
                  board.innerHTML = '';
                  COLUMNS.forEach(function (name, columnIndex) {
                    var column = document.createElement('div');
                    column.className = 'card';
                    var title = document.createElement('h2');
                    title.textContent = name;
                    column.appendChild(title);
                    var list = document.createElement('ul');
                    list.className = 'list';
                    list.style.marginTop = '12px';
                    cards.forEach(function (card, cardIndex) {
                      if (card.column !== columnIndex) { return; }
                      var li = document.createElement('li');
                      li.className = 'item';
                      li.style.cursor = 'pointer';
                      var text = document.createElement('span');
                      text.className = 'grow';
                      text.textContent = card.title;
                      li.appendChild(text);
                      li.onclick = function () {
                        cards[cardIndex].column = (cards[cardIndex].column + 1) % 3;
                        saveCards();
                        render();
                      };
                      list.appendChild(li);
                    });
                    column.appendChild(list);
                    board.appendChild(column);
                  });
                }
                document.getElementById('add').onclick = function () {
                  var title = prompt('任务名称');
                  if (!title) { return; }
                  cards.push({ title: title, column: 0 });
                  saveCards();
                  render();
                };
                loadCards();
                render();
                """;
        return new App("任务看板", "拖动式推进", "", body, script);
    }

    private static App calculator() {
        String body = """
                <div class="wrap stack" style="max-width:340px">
                  <h1>计算器</h1>
                  <div class="card" style="padding:16px">
                    <div style="text-align:right;font-size:13px;color:#8a8a85;height:18px" id="history"></div>
                    <div style="text-align:right;font-size:36px;font-weight:600;letter-spacing:-0.02em;
                      font-variant-numeric:tabular-nums;margin-bottom:14px" id="screen">0</div>
                    <div class="grid-3" style="gap:8px" id="pad"></div>
                  </div>
                </div>
                """;
        String script = """
                var KEYS = ['7','8','9','4','5','6','1','2','3','0','.','C','+','-','x','/','=','<'];
                var current = '0';
                var stored = null;
                var operator = null;
                function paint() {
                  document.getElementById('screen').textContent = current;
                  document.getElementById('history').textContent =
                    stored === null ? '' : stored + ' ' + (operator || '');
                }
                function compute() {
                  if (stored === null || operator === null) { return parseFloat(current) || 0; }
                  var a = parseFloat(stored);
                  var b = parseFloat(current);
                  if (operator === '+') { return a + b; }
                  if (operator === '-') { return a - b; }
                  if (operator === 'x') { return a * b; }
                  if (operator === '/') { return b === 0 ? 0 : a / b; }
                  return b;
                }
                function press(key) {
                  if (key >= '0' && key <= '9') {
                    current = current === '0' ? key : current + key;
                  } else if (key === '.') {
                    if (current.indexOf('.') < 0) { current = current + '.'; }
                  } else if (key === 'C') {
                    current = '0'; stored = null; operator = null;
                  } else if (key === '<') {
                    current = current.length > 1 ? current.slice(0, -1) : '0';
                  } else if (key === '=') {
                    current = String(parseFloat(compute().toFixed(10)));
                    stored = null; operator = null;
                  } else {
                    stored = String(compute());
                    current = '0';
                    operator = key;
                  }
                  paint();
                }
                var pad = document.getElementById('pad');
                KEYS.forEach(function (key) {
                  var button = document.createElement('button');
                  button.className = 'btn';
                  button.textContent = key === 'x' ? '×' : (key === '/' ? '÷' : key);
                  button.style.padding = '14px 0';
                  button.style.fontSize = '16px';
                  if (key === '=') { button.className = 'btn btn-primary'; }
                  button.onclick = function () { press(key); };
                  pad.appendChild(button);
                });
                paint();
                """;
        return new App("计算器", "四则运算", "", body, script);
    }

    private static App landing(String requirement) {
        String topic = requirement == null || requirement.isBlank() ? "我们的产品" : requirement;
        String body = """
                <div style="background:var(--panel);border-bottom:1px solid var(--border);margin:-24px -24px 0;padding:16px 24px">
                  <div class="wrap row" style="justify-content:space-between">
                    <strong>%s</strong>
                    <div class="row">
                      <span class="muted">功能</span>
                      <span class="muted">价格</span>
                      <button class="btn btn-primary">免费开始</button>
                    </div>
                  </div>
                </div>
                <div class="wrap" style="padding-top:56px">
                  <div style="text-align:center;max-width:620px;margin:0 auto">
                    <span class="badge">全新上线</span>
                    <h1 style="font-size:38px;line-height:1.25;margin:16px 0 12px">把想做的事，做得更简单</h1>
                    <p class="muted" style="font-size:15px">
                      %s。不需要复杂配置，打开就能开始，几分钟看到结果。
                    </p>
                    <div class="row" style="justify-content:center;margin-top:24px">
                      <button class="btn btn-primary" style="padding:11px 22px">立即免费使用</button>
                      <button class="btn" style="padding:11px 22px">看演示</button>
                    </div>
                  </div>
                  <div class="grid-3" style="margin-top:56px">
                    <div class="card">
                      <h2>上手极快</h2>
                      <p class="muted" style="margin-top:8px">不用看文档，界面本身就说明了一切。</p>
                    </div>
                    <div class="card">
                      <h2>稳定可靠</h2>
                      <p class="muted" style="margin-top:8px">每一步操作实时保存，不会白干。</p>
                    </div>
                    <div class="card">
                      <h2>价格透明</h2>
                      <p class="muted" style="margin-top:8px">免费版本就够用，升级随时可以。</p>
                    </div>
                  </div>
                  <div class="card" style="margin-top:56px;text-align:center;background:var(--accent-soft);border-color:transparent">
                    <h2 style="font-size:20px">今天就开始</h2>
                    <p class="muted" style="margin:8px 0 18px">两分钟注册，立刻用上。</p>
                    <button class="btn btn-primary" style="padding:11px 22px" onclick="alert('已记录你的意向')">免费注册</button>
                  </div>
                  <div class="muted" style="text-align:center;padding:40px 0 8px;font-size:13px">
                    © 2026 %s · 保留所有权利
                  </div>
                </div>
                """.formatted(shortName(topic), topic, shortName(topic));
        return new App(shortName(topic), "产品落地页", "", body, "");
    }

    private static App dashboard() {
        String body = """
                <div class="wrap stack" style="max-width:880px">
                  <div class="head">
                    <div>
                      <h1>数据概览</h1>
                      <div class="muted">最近 7 天的关键指标</div>
                    </div>
                    <span class="badge">最近 7 天</span>
                  </div>
                  <div class="grid-3">
                    <div class="card">
                      <div class="muted">总访问量</div>
                      <div class="stat" id="v1">0</div>
                    </div>
                    <div class="card">
                      <div class="muted">新增用户</div>
                      <div class="stat" id="v2">0</div>
                    </div>
                    <div class="card">
                      <div class="muted">转化率</div>
                      <div class="stat" id="v3">0</div>
                    </div>
                  </div>
                  <div class="card">
                    <h2>每日访问量</h2>
                    <div class="row" id="bars" style="align-items:flex-end;gap:14px;height:180px;margin-top:18px"></div>
                  </div>
                </div>
                """;
        String script = """
                var DATA = [
                  { day: '周一', value: 320 },
                  { day: '周二', value: 412 },
                  { day: '周三', value: 268 },
                  { day: '周四', value: 506 },
                  { day: '周五', value: 621 },
                  { day: '周六', value: 384 },
                  { day: '周日', value: 455 }
                ];
                var max = Math.max.apply(null, DATA.map(function (item) { return item.value; }));
                var container = document.getElementById('bars');
                DATA.forEach(function (item) {
                  var column = document.createElement('div');
                  column.style.flex = '1';
                  column.style.textAlign = 'center';
                  var bar = document.createElement('div');
                  bar.style.height = Math.round(item.value / max * 140) + 'px';
                  bar.style.background = 'var(--accent)';
                  bar.style.opacity = '0.85';
                  bar.style.borderRadius = '6px 6px 0 0';
                  var label = document.createElement('div');
                  label.className = 'muted';
                  label.style.fontSize = '12px';
                  label.style.marginTop = '8px';
                  label.textContent = item.day;
                  var amount = document.createElement('div');
                  amount.style.fontSize = '12px';
                  amount.textContent = item.value;
                  column.appendChild(amount);
                  column.appendChild(bar);
                  column.appendChild(label);
                  container.appendChild(column);
                });
                function sum(key) {
                  return DATA.reduce(function (acc, item) { return acc + item.value; }, 0);
                }
                function animate(element, target, suffix) {
                  var steps = 28;
                  var index = 0;
                  var timer = setInterval(function () {
                    index++;
                    var value = Math.round(target * index / steps);
                    element.textContent = value + suffix;
                    if (index >= steps) { clearInterval(timer); }
                  }, 22);
                }
                animate(document.getElementById('v1'), sum(), '');
                animate(document.getElementById('v2'), 1836, '');
                var rate = document.getElementById('v3');
                rate.textContent = '4.2%';
                """;
        return new App("数据概览", "关键指标看板", "", body, script);
    }

    private static App generic(String requirement) {
        String topic = requirement == null || requirement.isBlank() ? "应用" : requirement;
        String body = """
                <div class="wrap stack" style="max-width:720px">
                  <div class="head">
                    <div>
                      <h1>%s</h1>
                      <div class="muted">根据你的需求生成的初始版本</div>
                    </div>
                    <span class="badge">v1</span>
                  </div>
                  <div class="card">
                    <h2>需求说明</h2>
                    <p class="muted" style="margin-top:8px">%s</p>
                  </div>
                  <div class="card">
                    <h2>功能清单</h2>
                    <ul class="list" id="features" style="margin-top:12px"></ul>
                  </div>
                  <div class="card">
                    <h2>试一试</h2>
                    <div class="row" style="margin-top:12px">
                      <input class="input" id="draft" placeholder="输入点什么…">
                      <button class="btn btn-primary" id="submit">添加</button>
                    </div>
                    <ul class="list" id="records" style="margin-top:14px"></ul>
                  </div>
                </div>
                """.formatted(shortName(topic), topic);
        String script = """
                var FEATURES = ['数据录入与列表展示', '即时交互反馈', '本地持久化', '响应式布局'];
                var features = document.getElementById('features');
                FEATURES.forEach(function (text) {
                  var li = document.createElement('li');
                  li.className = 'item';
                  var dot = document.createElement('span');
                  dot.style.width = '6px';
                  dot.style.height = '6px';
                  dot.style.borderRadius = '50%';
                  dot.style.background = 'var(--accent)';
                  var label = document.createElement('span');
                  label.className = 'grow';
                  label.textContent = text;
                  li.appendChild(dot);
                  li.appendChild(label);
                  features.appendChild(li);
                });
                var records = [];
                function render() {
                  var list = document.getElementById('records');
                  list.innerHTML = '';
                  records.forEach(function (text, index) {
                    var li = document.createElement('li');
                    li.className = 'item';
                    var label = document.createElement('span');
                    label.className = 'grow';
                    label.textContent = text;
                    var remove = document.createElement('button');
                    remove.className = 'btn';
                    remove.textContent = '删除';
                    remove.onclick = function () {
                      records.splice(index, 1);
                      render();
                    };
                    li.appendChild(label);
                    li.appendChild(remove);
                    list.appendChild(li);
                  });
                }
                document.getElementById('submit').onclick = function () {
                  var input = document.getElementById('draft');
                  var value = input.value.trim();
                  if (!value) { return; }
                  records.push(value);
                  input.value = '';
                  render();
                };
                render();
                """;
        return new App(shortName(topic), "初始实现", "", body, script);
    }

    private static String shortName(String text) {
        if (text == null || text.isBlank()) {
            return "新应用";
        }
        String cleaned = text.replaceAll("[，。,.、\\s]+$", "").trim();
        return cleaned.length() > 18 ? cleaned.substring(0, 18) : cleaned;
    }
}
