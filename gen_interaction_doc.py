from docx import Document
from docx.shared import Pt, RGBColor, Inches
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT

# 创建文档
doc = Document()

# 设置默认字体
style = doc.styles['Normal']
font = style.font
font.name = '微软雅黑'
font.size = Pt(11)

# ========== 标题 ==========
title = doc.add_heading('RPO 机器人表情系统', level=0)
title.alignment = WD_ALIGN_PARAGRAPH.CENTER

subtitle = doc.add_paragraph()
subtitle.alignment = WD_ALIGN_PARAGRAPH.CENTER
run = subtitle.add_run('软硬件交互逻辑文档')
run.font.size = Pt(18)
run.font.bold = True
run.font.color.rgb = RGBColor(0x4F, 0xC3, 0xF7)

doc.add_paragraph()
doc.add_paragraph()

# 版本信息表
info_table = doc.add_table(rows=4, cols=2)
info_table.style = 'Table Grid'
info_table.alignment = WD_TABLE_ALIGNMENT.CENTER
info_data = [
    ("文档版本", "V1.0"),
    ("创建日期", "2026-07-27"),
    ("适用项目", "RPO 纯眼部表情系统"),
    ("涉及模块", "Android APP / RDK 嵌入式 / 结构硬件"),
]
for i, (k, v) in enumerate(info_data):
    info_table.rows[i].cells[0].text = k
    info_table.rows[i].cells[1].text = v

doc.add_page_break()

# ========== 一、概述 ==========
doc.add_heading('一、概述', level=1)

doc.add_heading('1.1 文档目的', level=2)
p = doc.add_paragraph()
p.add_run('本文档定义 RPO 机器人表情系统中 Android APP（屏幕显示端）与 RDK 主控板（嵌入式端）之间的软硬件交互逻辑，包括通信链路、指令协议、数据流向、联调规范及扩展预留接口，为三方（软件、嵌入式、结构）协作开发提供统一参考标准。')

doc.add_heading('1.2 适用范围', level=2)
p = doc.add_paragraph()
p.add_run('本规范适用于以下场景：')
items = [
    '表情控制指令下发与状态同步',
    '头部电机运动控制（摇头、点头、人脸跟踪）',
    '感知数据上行上报（人脸、触摸、语音、IMU）',
    '系统状态查询与配置热更新',
    '软硬件联调测试与故障排查',
]
for item in items:
    doc.add_paragraph(item, style='List Bullet')

doc.add_heading('1.3 术语定义', level=2)
term_table = doc.add_table(rows=1, cols=2)
term_table.style = 'Table Grid'
hdr = term_table.rows[0].cells
hdr[0].text = '术语'
hdr[1].text = '说明'

terms = [
    ("主控 / RDK", "机器人主控制板，运行嵌入式程序，是整机的控制中枢"),
    ("APP / 表情端", "Android 应用，运行于机器人头部屏幕，负责渲染眼部表情"),
    ("表情控制链路", "主控 → APP 的下行控制通道，端口 8899 TCP"),
    ("头部控制链路", "APP → 嵌入式 的下行电机控制通道，端口 8765 TCP"),
    ("感知上报", "APP → 主控 的上行数据通道，人脸/触摸/语音等传感器数据"),
    ("Protobuf", "Google 二进制序列化协议，用于高效数据传输"),
    ("VAD", "语音活动检测，判断用户是否在说话"),
]
for term, desc in terms:
    row = term_table.add_row().cells
    row[0].text = term
    row[1].text = desc

# ========== 二、整体架构 ==========
doc.add_heading('二、整体架构', level=1)

doc.add_heading('2.1 系统分层架构', level=2)
p = doc.add_paragraph()
p.add_run('系统采用三层解耦架构，各层职责明确，通过标准化协议交互：')

arch_table = doc.add_table(rows=1, cols=3)
arch_table.style = 'Table Grid'
hdr = arch_table.rows[0].cells
hdr[0].text = '层级'
hdr[1].text = '模块'
hdr[2].text = '核心职责'

arch_data = [
    ("应用层", "Android APP", "表情渲染、人脸检测、触摸交互、语音交互、UI展示"),
    ("控制层", "RDK 主控", "指令调度、状态管理、电机控制、传感器融合、决策逻辑"),
    ("执行层", "结构硬件", "头部电机、屏幕、麦克风、扬声器、物理按键"),
]
for layer, module, duty in arch_data:
    row = arch_table.add_row().cells
    row[0].text = layer
    row[1].text = module
    row[2].text = duty

doc.add_heading('2.2 数据流向总览', level=2)
p = doc.add_paragraph()
p.add_run('系统存在两条独立下行控制链路 + 一条上行感知链路：')

flow_items = [
    '下行链路 A（表情控制）：主控 → APP，端口 8899，控制屏幕表情显示',
    '下行链路 B（头部电机）：APP → 嵌入式，端口 8765，控制头部物理运动',
    '上行链路（感知数据）：APP → 主控，端口 8900 UDP，上报人脸/触摸/语音等感知事件',
]
for item in flow_items:
    doc.add_paragraph(item, style='List Bullet')

doc.add_paragraph()
p = doc.add_paragraph()
p.add_run('※ 注：两条下行链路相互独立，表情显示与头部运动互不阻塞，单一链路故障不影响另一链路运行。')
p.runs[0].font.color.rgb = RGBColor(0xFF, 0x98, 0x00)

# ========== 三、通信链路详解 ==========
doc.add_heading('三、通信链路详解', level=1)

doc.add_heading('3.1 链路对比总表', level=2)
link_table = doc.add_table(rows=1, cols=4)
link_table.style = 'Table Grid'
hdr = link_table.rows[0].cells
hdr[0].text = '维度'
hdr[1].text = '表情控制链路（链路A）'
hdr[2].text = '头部控制链路（链路B）'
hdr[3].text = '感知上报链路'

link_data = [
    ("方向", "主控 → APP", "APP → 嵌入式", "APP → 主控"),
    ("端口", "8899 TCP / 8900 UDP", "8765 TCP", "8900 UDP"),
    ("封包格式", "2字节大端序长度头 + Protobuf", "4字节大端序长度头 + Protobuf", "Protobuf 直接发送"),
    ("连接模式", "长连接，自动重连", "长连接，自动重连", "无连接，UDP 直接上报"),
    ("心跳间隔", "3秒", "3秒", "无心跳，事件驱动"),
    ("超时断连", "5秒", "5秒", "-"),
    ("主要数据", "表情指令、配置、状态查询", "情绪指令、运动模式、人脸跟踪", "人脸、触摸、语音、IMU 事件"),
]
for dim, a, b, c in link_data:
    row = link_table.add_row().cells
    row[0].text = dim
    row[1].text = a
    row[2].text = b
    row[3].text = c

doc.add_heading('3.2 表情控制链路（链路A）', level=2)

doc.add_heading('3.2.1 核心指令集', level=3)
cmd_table = doc.add_table(rows=1, cols=3)
cmd_table.style = 'Table Grid'
hdr = cmd_table.rows[0].cells
hdr[0].text = '指令 cmd'
hdr[1].text = '用途'
hdr[2].text = '关键参数'

cmd_data = [
    ("expression", "切换表情", "type / duration / force"),
    ("sequence", "播放表情序列", "data.types 数组"),
    ("play_demo", "标准演示流程", "无参数"),
    ("clear", "停止/清屏回待机", "无参数"),
    ("set_style", "切换渲染风格", "style（4种风格+2种性别）"),
    ("set_intensity", "设置表情强度", "intensity（0~100）"),
    ("brightness", "设置屏幕亮度", "value（0.0~1.0）"),
    ("status", "查询当前状态", "无参数，返回设备状态 JSON"),
    ("config", "热更新配置参数", "增量更新，无需重启"),
    ("reset_config", "重置默认配置", "无参数"),
    ("ping/pong", "心跳保活", "timestamp"),
]
for cmd, use, param in cmd_data:
    row = cmd_table.add_row().cells
    row[0].text = cmd
    row[1].text = use
    row[2].text = param

doc.add_heading('3.2.2 表情优先级规则', level=3)
p = doc.add_paragraph()
p.add_run('高优先级抢占低优先级，同优先级新覆盖旧，低优先级默认被拒绝（force=true 可强制）：')
prio_items = [
    'Lv6（最高）：ALERT 告警、UPDATING 升级中',
    'Lv5：SPEAKING 说话',
    'Lv4：RESPONSE、HAPPY、SURPRISED、SAD、ANGRY、SHY',
    'Lv3：CONFUSED 疑惑',
    'Lv2：RECOGNIZING 识别中',
    'Lv1：THINKING、SLEEPY、LOW_BATTERY、CHARGING',
    'Lv0（最低）：IDLE、BLINK、SLEEPING',
]
for item in prio_items:
    doc.add_paragraph(item, style='List Bullet')

doc.add_heading('3.3 头部控制链路（链路B）', level=2)

doc.add_heading('3.3.1 协议定义', level=3)
p = doc.add_paragraph()
p.add_run('基于 Protobuf 序列化，4 字节大端序长度头解决 TCP 粘包问题。')

doc.add_heading('3.3.2 核心指令（已实现）', level=3)
head_table = doc.add_table(rows=1, cols=3)
head_table.style = 'Table Grid'
hdr = head_table.rows[0].cells
hdr[0].text = '指令类型'
hdr[1].text = '情绪 / 运动模式'
hdr[2].text = '触发场景'

head_data = [
    ("HeadControlCmd", "EMOTION_CONFUSION + MOVE_SHAKE", "疑惑表情 → 摇头动作"),
    ("HeadControlCmd", "EMOTION_HAPPY + MOVE_NOD", "开心表情 → 点头动作"),
    ("HeadControlCmd", "EMOTION_NONE + MOVE_NONE", "待机/其他表情 → 停止动作"),
]
for cmd, mode, scene in head_data:
    row = head_table.add_row().cells
    row[0].text = cmd
    row[1].text = mode
    row[2].text = scene

doc.add_heading('3.3.3 表情联动规则', level=3)
p = doc.add_paragraph()
p.add_run('APP 端表情状态变化时，自动联动头部电机动作：')
linkage_items = [
    'CONFUSED 疑惑表情触发 → 自动发送摇头指令',
    'HAPPY 开心表情触发 → 自动发送点头指令',
    'IDLE / BLINK / 其他表情 → 自动发送停止指令',
    '表情优先级体系同样适用于头部动作，高优先级表情抢占时动作同步切换',
]
for item in linkage_items:
    doc.add_paragraph(item, style='List Bullet')

doc.add_heading('3.3.4 设备上行反馈（预留）', level=3)
p = doc.add_paragraph()
p.add_run('DeviceFeedbackEvent 设备反馈事件，预留用于传感器数据上行：')
fb_items = [
    'event_code：事件码（1=拍打头部、2=触摸脸部、3=机身晃动，待定义）',
    'sensor_value：传感器采集数值',
    'device_online：设备运行状态',
]
for item in fb_items:
    doc.add_paragraph(item, style='List Bullet')

doc.add_heading('3.4 感知上报链路（上行）', level=2)
p = doc.add_paragraph()
p.add_run('APP 通过 UDP 高频上报感知事件，主控按需订阅处理：')

percept_table = doc.add_table(rows=1, cols=2)
percept_table.style = 'Table Grid'
hdr = percept_table.rows[0].cells
hdr[0].text = '事件类型'
hdr[1].text = '说明'

percept_data = [
    ("event_expression_changed", "表情变化事件，通知主控当前表情状态"),
    ("event_face_detected", "人脸检测事件，含 gaze_x/gaze_y 偏移量（-1~1）、人脸数量、置信度"),
    ("event_voice_state", "语音状态事件（wake_up_listening / listening / thinking / speaking / barge_in / idle）"),
    ("event_head_motion", "头部姿态 IMU 事件（nod 点头 / shake 摇头 / look_up 抬头 / look_down 低头 / shake_hard 剧烈晃动）"),
    ("event_touch", "触摸事件（预留，点击位置、手势类型）"),
]
for event, desc in percept_data:
    row = percept_table.add_row().cells
    row[0].text = event
    row[1].text = desc

# ========== 四、人脸跟踪交互（待开发） ==========
doc.add_heading('四、人脸跟踪交互（待开发）', level=1)

doc.add_heading('4.1 功能概述', level=2)
p = doc.add_paragraph()
p.add_run('利用手机前置摄像头 + ML Kit 人脸检测，实现机器人头部自动跟随人脸转动的功能。APP 负责检测计算，嵌入式负责电机控制。')

doc.add_heading('4.2 数据流向', level=2)
flow_steps = [
    '手机摄像头采集实时画面',
    'ML Kit 检测人脸位置，计算归一化偏移量（-1~1）',
    'APP 平滑滤波 + 死区判断 + 频率限制',
    '通过头部控制链路（8765 TCP）下发 FaceTrackingCmd',
    '嵌入式接收目标偏移，换算为电机角度，PID 控制平滑转动',
    '硬件限位保护，防止超角度旋转',
]
for i, step in enumerate(flow_steps, 1):
    doc.add_paragraph(f'{i}. {step}', style='List Number')

doc.add_heading('4.3 协议设计（预留）', level=2)
proto_table = doc.add_table(rows=1, cols=3)
proto_table.style = 'Table Grid'
hdr = proto_table.rows[0].cells
hdr[0].text = '字段'
hdr[1].text = '类型'
hdr[2].text = '说明'

proto_data = [
    ("target_yaw", "float", "水平目标偏移/角度，-1~1 或角度值（待对齐）"),
    ("target_pitch", "float", "垂直目标偏移/角度，-1~1 或角度值（待对齐）"),
    ("confidence", "float", "人脸置信度 0~1"),
    ("tracking", "bool", "是否处于跟踪状态，false=停止回正"),
]
for field, typ, desc in proto_data:
    row = proto_table.add_row().cells
    row[0].text = field
    row[1].text = typ
    row[2].text = desc

doc.add_heading('4.4 待对齐参数', level=2)
todo_table = doc.add_table(rows=1, cols=3)
todo_table.style = 'Table Grid'
hdr = todo_table.rows[0].cells
hdr[0].text = '参数项'
hdr[1].text = '建议值'
hdr[2].text = '负责方'

todo_data = [
    ("水平转动范围", "±45°", "嵌入式 + 结构"),
    ("垂直转动范围", "±20°", "嵌入式 + 结构"),
    ("控制模式", "位置模式 / 速度模式", "嵌入式"),
    ("上报频率", "10Hz（100ms/次）", "软件"),
    ("死区阈值", "±0.08（防抖）", "软件"),
    ("无人脸超时", "2秒回正", "软件 + 嵌入式"),
    ("PID 参数", "待调试", "嵌入式"),
]
for param, suggest, owner in todo_data:
    row = todo_table.add_row().cells
    row[0].text = param
    row[1].text = suggest
    row[2].text = owner

# ========== 五、联调规范 ==========
doc.add_heading('五、联调规范与测试方法', level=1)

doc.add_heading('5.1 联调前检查清单', level=2)
check_items = [
    '两端设备处于同一局域网，可互相 ping 通',
    '端口号确认一致：表情 8899 / 头部 8765 / UDP 8900',
    '封包格式对齐：长度头字节数、大小端、Protobuf 字段编号',
    '心跳机制验证：3秒心跳、5秒超时断连、自动重连',
    '单客户端限制确认：新连接替换旧连接行为符合预期',
]
for item in check_items:
    doc.add_paragraph(item, style='List Bullet')

doc.add_heading('5.2 分阶段联调建议', level=2)

doc.add_heading('阶段一：链路通断测试', level=3)
stage1 = [
    'APP 主动连接，观察连接状态指示灯',
    '断开网络，验证 5 秒超时断连',
    '恢复网络，验证自动重连机制',
    '多次重复连接断开，验证无内存泄漏',
]
for item in stage1:
    doc.add_paragraph(item, style='List Bullet')

doc.add_heading('阶段二：指令收发测试', level=3)
stage2 = [
    '主控下发 expression 指令，验证表情正确切换',
    'APP 触发疑惑表情，验证头部电机正确摇头',
    'APP 触发开心表情，验证头部电机正确点头',
    '连续快速切换表情，验证电机响应与优先级',
    '发送 status 指令，验证状态查询返回正确',
]
for item in stage2:
    doc.add_paragraph(item, style='List Bullet')

doc.add_heading('阶段三：场景联调测试', level=3)
stage3 = [
    '人脸检测 → 表情联动 → 头部动作 全链路测试',
    '语音对话 → 识别中/思考/说话 表情同步测试',
    '低电量 / 充电 / 告警 系统状态优先级测试',
    '长时间运行稳定性测试（建议连续运行 2 小时以上）',
]
for item in stage3:
    doc.add_paragraph(item, style='List Bullet')

doc.add_heading('5.3 故障排查指南', level=2)
debug_table = doc.add_table(rows=1, cols=2)
debug_table.style = 'Table Grid'
hdr = debug_table.rows[0].cells
hdr[0].text = '现象'
hdr[1].text = '排查方向'

debug_data = [
    ("连接失败 / 超时", "1. 检查 IP 和端口是否正确\n2. 确认两端在同一局域网\n3. 检查防火墙是否拦截\n4. 确认 RDK 端服务已启动"),
    ("表情指令不生效", "1. 检查优先级是否被更高优先级抢占\n2. 检查 force 参数是否为 false 被拒绝\n3. 查看 APP 端日志是否收到指令"),
    ("头部电机不动", "1. 确认 8765 端口连接正常\n2. 检查封包格式（4字节长度头大小端）\n3. 确认 Protobuf 字段编号一致\n4. 检查电机使能与供电"),
    ("动作卡顿 / 延迟", "1. 检查网络延迟（ping 测试）\n2. 确认 TCP_NODELAY 已开启\n3. 检查发送频率是否过高\n4. 嵌入式 PID 参数是否过阻尼"),
    ("频繁断连", "1. 网络稳定性测试\n2. 心跳间隔与超时是否合理\n3. 是否有其他客户端抢占连接"),
]
for symptom, solution in debug_data:
    row = debug_table.add_row().cells
    row[0].text = symptom
    row[1].text = solution

doc.add_page_break()

# ========== 六、未来扩展方向（预留空白） ==========
doc.add_heading('六、未来扩展方向', level=1)

p = doc.add_paragraph()
p.add_run('以下章节预留空白，用于软件、嵌入式、结构三方分别填写未来扩展规划。')
p.runs[0].font.color.rgb = RGBColor(0x9E, 0x9E, 0x9E)

# 6.1 软件扩展方向
doc.add_heading('6.1 软件扩展方向', level=2)
p = doc.add_paragraph()
p.add_run('（预留空白 · 待软件团队填写）')
p.runs[0].font.color.rgb = RGBColor(0xBD, 0xBD, 0xBD)

# 预留空白行
for _ in range(15):
    doc.add_paragraph()

# 6.2 嵌入式扩展方向
doc.add_heading('6.2 嵌入式扩展方向', level=2)
p = doc.add_paragraph()
p.add_run('（预留空白 · 待嵌入式团队填写）')
p.runs[0].font.color.rgb = RGBColor(0xBD, 0xBD, 0xBD)

# 预留空白行
for _ in range(15):
    doc.add_paragraph()

# 6.3 结构扩展方向
doc.add_heading('6.3 结构扩展方向', level=2)
p = doc.add_paragraph()
p.add_run('（预留空白 · 待结构团队填写）')
p.runs[0].font.color.rgb = RGBColor(0xBD, 0xBD, 0xBD)

# 预留空白行
for _ in range(15):
    doc.add_paragraph()

# ========== 七、附录 ==========
doc.add_heading('七、附录', level=1)

doc.add_heading('7.1 端口号汇总', level=2)
port_table = doc.add_table(rows=1, cols=3)
port_table.style = 'Table Grid'
hdr = port_table.rows[0].cells
hdr[0].text = '端口'
hdr[1].text = '协议'
hdr[2].text = '用途'

port_data = [
    ("8899", "TCP", "表情控制主链路（主控 → APP）"),
    ("8900", "UDP", "感知数据上报 + 备用表情控制"),
    ("8765", "TCP", "头部电机控制链路（APP → 嵌入式）"),
]
for port, proto, use in port_data:
    row = port_table.add_row().cells
    row[0].text = port
    row[1].text = proto
    row[2].text = use

doc.add_heading('7.2 相关文档', level=2)
ref_items = [
    '《机器人表情系统规范文档》—— 表情系统完整规范',
    '《roboto_origin 表情模块通信接口规范》—— TCP 通信协议 v2.0',
    '《子任务3.1 通信架构升级联调报告》—— Protobuf + 多链路通信',
    '《RDK 硬件联调新手手把手测试指南》—— 联调测试操作手册',
    '《APP 部署与调试手册》—— Android 端部署与调试',
]
for item in ref_items:
    doc.add_paragraph(item, style='List Bullet')

doc.add_heading('7.3 修订记录', level=2)
rev_table = doc.add_table(rows=1, cols=4)
rev_table.style = 'Table Grid'
hdr = rev_table.rows[0].cells
hdr[0].text = '版本'
hdr[1].text = '日期'
hdr[2].text = '修订人'
hdr[3].text = '修订内容'

row = rev_table.add_row().cells
row[0].text = 'V1.0'
row[1].text = '2026-07-27'
row[2].text = ''
row[3].text = '初始版本，梳理现有两条通信链路 + 人脸跟踪预留设计'

# 保存文档
output_path = r"F:\workspace\RPO软硬件交互逻辑文档.docx"
doc.save(output_path)
print(f"文档生成完成: {output_path}")
