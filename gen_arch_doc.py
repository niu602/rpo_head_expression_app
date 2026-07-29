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
run = subtitle.add_run('系统总体架构')
run.font.size = Pt(20)
run.font.bold = True
run.font.color.rgb = RGBColor(0x4F, 0xC3, 0xF7)

doc.add_paragraph()

# ========== 一、系统拓扑图 ==========
doc.add_heading('一、系统拓扑图', level=1)

# 拓扑图说明
p = doc.add_paragraph()
p.add_run('系统采用三层物理拓扑，手机端与板端通过 WiFi 无线通信，板端与执行硬件通过总线直连：')

# 拓扑图（用表格模拟）
doc.add_paragraph()
topo_table = doc.add_table(rows=3, cols=5)
topo_table.alignment = WD_TABLE_ALIGNMENT.CENTER

# 设置列宽
for row in topo_table.rows:
    row.cells[0].width = Inches(2.5)
    row.cells[1].width = Inches(1.5)
    row.cells[2].width = Inches(2.5)
    row.cells[3].width = Inches(1.5)
    row.cells[4].width = Inches(2.5)

# 第一行：设备名称
topo_table.rows[0].cells[0].text = '📱 手机端（Android）'
topo_table.rows[0].cells[1].text = ''
topo_table.rows[0].cells[2].text = '🎛️ RDK 主控板'
topo_table.rows[0].cells[3].text = ''
topo_table.rows[0].cells[4].text = '⚙️ 执行硬件'

# 第二行：连接方式
topo_table.rows[1].cells[0].text = ''
topo_table.rows[1].cells[1].text = 'WiFi 局域网\nTCP / UDP'
topo_table.rows[1].cells[2].text = ''
topo_table.rows[1].cells[3].text = 'GPIO / PWM\n串口总线'
topo_table.rows[1].cells[4].text = ''

# 第三行：具体模块
topo_table.rows[2].cells[0].text = '• 表情渲染\n• 人脸检测\n• 触摸交互\n• 语音 ASR/TTS\n• 屏幕显示'
topo_table.rows[2].cells[1].text = ''
topo_table.rows[2].cells[2].text = '• 指令调度\n• 状态管理\n• 电机控制\n• 传感器融合\n• 决策逻辑'
topo_table.rows[2].cells[3].text = ''
topo_table.rows[2].cells[4].text = '• 头部电机（Yaw/Pitch）\n• 物理触摸传感器\n• IMU 姿态传感器\n• 扬声器 / 麦克风'

# 设置表格样式
for row in topo_table.rows:
    for cell in row.cells:
        for para in cell.paragraphs:
            para.alignment = WD_ALIGN_PARAGRAPH.CENTER
            for run in para.runs:
                run.font.size = Pt(10)

doc.add_paragraph()

# 通信链路详情表
doc.add_heading('1.1 通信链路详情', level=2)
comm_table = doc.add_table(rows=1, cols=5)
comm_table.style = 'Table Grid'
hdr = comm_table.rows[0].cells
hdr[0].text = '链路名称'
hdr[1].text = '方向'
hdr[2].text = '物理层'
hdr[3].text = '协议 / 端口'
hdr[4].text = '主要数据'

comm_data = [
    ("表情控制链路", "板端 → 手机", "WiFi TCP", "8899 端口 / Protobuf / 2字节长度头", "表情指令、风格切换、亮度、配置热更新"),
    ("头部控制链路", "手机 → 板端", "WiFi TCP", "8765 端口 / Protobuf / 4字节长度头", "情绪指令、运动模式、人脸跟踪目标"),
    ("感知上报链路", "手机 → 板端", "WiFi UDP", "8900 端口 / Protobuf", "人脸位置、触摸事件、语音状态、IMU 姿态"),
    ("电机控制总线", "板端 → 电机", "GPIO / PWM / 串口", "硬件总线协议", "电机转角、速度、使能控制"),
    ("传感器回传", "传感器 → 板端", "I2C / ADC / 中断", "硬件总线", "触摸触发、IMU 数据、电量检测"),
]
for name, direction, phy, proto, data in comm_data:
    row = comm_table.add_row().cells
    row[0].text = name
    row[1].text = direction
    row[2].text = phy
    row[3].text = proto
    row[4].text = data

doc.add_paragraph()

# 备用链路
doc.add_heading('1.2 备用冗余链路（预留）', level=2)
backup_items = [
    'USB 串口：WiFi 断开时通过 Type-C 直连兜底，走串口 AT 指令',
    '蓝牙 BLE：近场备用控制，手机蓝牙直接连接板子',
    '设计目标：故障切换 < 1 秒，保证表情显示不中断',
]
for item in backup_items:
    doc.add_paragraph(item, style='List Bullet')

doc.add_page_break()

# ========== 二、三层架构划分 ==========
doc.add_heading('二、三层架构划分', level=1)

p = doc.add_paragraph()
p.add_run('系统自下而上分为结构层、嵌入式层、软件层，各层职责明确，通过标准化接口解耦：')

doc.add_paragraph()

# 三层总览表
layer_table = doc.add_table(rows=1, cols=4)
layer_table.style = 'Table Grid'
hdr = layer_table.rows[0].cells
hdr[0].text = '层级'
hdr[1].text = '一句话职责定义'
hdr[2].text = '负责团队'
hdr[3].text = '核心模块'

layer_data = [
    ("软件层\n（手机端）", "负责表情渲染、感知计算与人机交互，输出视觉表现与感知数据", "⭐ 你负责", "表情渲染引擎、人脸检测、语音交互、触摸交互、通信客户端"),
    ("嵌入式层\n（板端）", "负责整机指令调度、电机控制与传感器融合，是系统的控制中枢", "嵌入式团队", "TCP/UDP 服务端、状态机、电机驱动、传感器采集、决策逻辑"),
    ("结构层\n（硬件）", "负责机械装配、物理接口与硬件载体，提供运动能力与传感器输入", "结构团队", "头部电机、外壳结构、屏幕固定、传感器布局、供电系统"),
]
for layer, duty, owner, modules in layer_data:
    row = layer_table.add_row().cells
    row[0].text = layer
    row[1].text = duty
    row[2].text = owner
    row[3].text = modules

# 高亮"你负责"
for row in layer_table.rows:
    if "你负责" in row.cells[2].text:
        for para in row.cells[2].paragraphs:
            for run in para.runs:
                run.font.color.rgb = RGBColor(0xFF, 0x57, 0x22)
                run.font.bold = True

doc.add_paragraph()
doc.add_paragraph()

# ========== 三、各层详细职责 ==========
doc.add_heading('三、各层详细职责边界', level=1)

# 软件层
doc.add_heading('3.1 软件层（手机端 · 你负责）', level=2)
p = doc.add_paragraph()
run = p.add_run('⭐ 你的负责范围：Android APP 全部开发，包括表情渲染、人脸视觉、语音交互、触摸交互、通信协议客户端。')
run.font.color.rgb = RGBColor(0xFF, 0x57, 0x22)
run.font.bold = True

doc.add_heading('核心职责：', level=3)
soft_duties = [
    '表情渲染：18 种标准表情绘制、5 类眼内特效、4 种渲染风格、2 种性别风格切换',
    '视觉感知：ML Kit 人脸检测、视线跟随、表情镜像、多人脸交互、休眠唤醒',
    '语音交互：ASR 实时语音识别、LLM 对话、TTS 语音播报、打断机制',
    '触摸交互：边缘滑动调亮度/音量、点击触发表情、长按开关机、双指捏合切换性别、双击刷新',
    '通信客户端：表情控制链路（8899 TCP 客户端）、头部控制链路（8765 TCP 客户端）、感知上报（8900 UDP）',
    '系统状态：电量显示、充电状态、错误告警、亮度管理、音量管理',
    '配置管理：7 大类 60+ 参数热更新、增量配置同步',
]
for item in soft_duties:
    doc.add_paragraph(item, style='List Bullet')

doc.add_heading('输出接口（给嵌入式层）：', level=3)
soft_output = [
    'HeadControlCmd：头部电机控制指令（疑惑摇头、开心点头、停止、人脸跟踪）',
    'PerceptionEvent：感知事件上报（人脸位置、触摸、语音状态、IMU）',
    'Status Response：设备状态查询响应',
]
for item in soft_output:
    doc.add_paragraph(item, style='List Bullet')

doc.add_heading('输入接口（来自嵌入式层）：', level=3)
soft_input = [
    'Expression Command：表情切换、风格切换、亮度调节、配置更新',
    'Sequence Command：表情序列播放、Demo 演示',
    'Ping / Config：心跳、热更新配置',
]
for item in soft_input:
    doc.add_paragraph(item, style='List Bullet')

doc.add_paragraph()

# 嵌入式层
doc.add_heading('3.2 嵌入式层（RDK 板端）', level=2)
p = doc.add_paragraph()
p.add_run('负责整机控制与调度，是连接软件层和结构层的中间枢纽。')

doc.add_heading('核心职责：', level=3)
embed_duties = [
    '通信服务端：8899 TCP 表情控制服务、8765 TCP 头部控制服务、8900 UDP 接收',
    '状态管理：全局状态机、表情优先级仲裁、系统错误等级管理',
    '电机控制：头部 Yaw/Pitch 双轴电机驱动、PID 平滑控制、限位保护',
    '传感器融合：物理触摸传感器、IMU 姿态、电量检测等硬件数据采集',
    '决策逻辑：语音对话调度、场景模式切换、告警逻辑',
    '多链路管理：TCP 主 / UDP 备 / USB 备 / BLE 备 链路切换与冗余',
]
for item in embed_duties:
    doc.add_paragraph(item, style='List Bullet')

doc.add_heading('输出接口（给软件层）：', level=3)
embed_output = [
    'ExpressionCmd：表情控制指令下发',
    'ConfigCmd：配置热更新下发',
    'StatusQuery：状态查询指令',
]
for item in embed_output:
    doc.add_paragraph(item, style='List Bullet')

doc.add_heading('输入接口（来自软件层）：', level=3)
embed_input = [
    'HeadControlCmd：头部电机控制指令',
    'PerceptionEvent：感知事件上报',
    'Pong / Status：心跳响应、状态上报',
]
for item in embed_input:
    doc.add_paragraph(item, style='List Bullet')

doc.add_heading('输出接口（给结构层）：', level=3)
embed_hw_output = [
    '电机 PWM / 方向信号：控制头部转动角度与速度',
    '屏幕供电 / 亮度控制信号（如硬件支持）',
]
for item in embed_hw_output:
    doc.add_paragraph(item, style='List Bullet')

doc.add_heading('输入接口（来自结构层）：', level=3)
embed_hw_input = [
    '电机编码器反馈：实际转角回传',
    '传感器中断：触摸触发、IMU 数据、限位开关',
    '电量 / 充电状态 ADC 采样',
]
for item in embed_hw_input:
    doc.add_paragraph(item, style='List Bullet')

doc.add_paragraph()

# 结构层
doc.add_heading('3.3 结构层（硬件机械）', level=2)
p = doc.add_paragraph()
p.add_run('负责物理载体与执行机构，提供运动能力、传感输入和结构支撑。')

doc.add_heading('核心职责：', level=3)
struct_duties = [
    '机械结构：头部外壳、屏幕固定支架、电机安装座、轴承与传动结构',
    '运动系统：水平 Yaw 电机（左右转头）、垂直 Pitch 电机（上下点头）、减速机构',
    '传感器布局：物理触摸传感器位置、IMU 安装位置、麦克风 / 扬声器开孔',
    '供电系统：整机供电方案、电池管理、充电接口',
    '硬件接口：电机与主控板接线、传感器接线定义',
    '限位保护：机械限位结构、防过转设计',
]
for item in struct_duties:
    doc.add_paragraph(item, style='List Bullet')

doc.add_heading('输出接口（给嵌入式层）：', level=3)
struct_output = [
    '电机执行：接收 PWM/方向信号，输出物理转动',
    '传感器数据：触摸、IMU、电量等原始硬件信号',
    '限位信号：机械限位开关触发信号',
]
for item in struct_output:
    doc.add_paragraph(item, style='List Bullet')

doc.add_heading('输入接口（来自嵌入式层）：', level=3)
struct_input = [
    '电机驱动信号：PWM 占空比、方向电平、使能信号',
    '供电控制：开关机、睡眠唤醒控制信号',
]
for item in struct_input:
    doc.add_paragraph(item, style='List Bullet')

doc.add_page_break()

# ========== 四、接口契约总览 ==========
doc.add_heading('四、层间接口契约总览', level=1)

p = doc.add_paragraph()
p.add_run('三层之间通过标准化协议对接，接口变更需双方同步确认：')

# 接口契约表
contract_table = doc.add_table(rows=1, cols=4)
contract_table.style = 'Table Grid'
hdr = contract_table.rows[0].cells
hdr[0].text = '接口位置'
hdr[1].text = '接口名称'
hdr[2].text = '协议格式'
hdr[3].text = '变更影响'

contract_data = [
    ("软件 ↔ 嵌入式", "表情控制指令", "Protobuf + 2字节长度头 / TCP", "影响表情显示，需软件嵌入式同步改"),
    ("软件 ↔ 嵌入式", "头部控制指令", "Protobuf + 4字节长度头 / TCP", "影响头部电机，需软件嵌入式同步改"),
    ("软件 ↔ 嵌入式", "感知事件上报", "Protobuf / UDP", "影响主控决策，需软件嵌入式同步改"),
    ("嵌入式 ↔ 结构", "电机驱动信号", "PWM + GPIO 电平 / 硬件接线", "影响电机运动，需嵌入式结构同步调"),
    ("嵌入式 ↔ 结构", "传感器信号", "ADC / 中断 / I2C / 硬件接线", "影响感知输入，需嵌入式结构同步调"),
]
for pos, name, proto, impact in contract_data:
    row = contract_table.add_row().cells
    row[0].text = pos
    row[1].text = name
    row[2].text = proto
    row[3].text = impact

doc.add_paragraph()
doc.add_paragraph()

# ========== 五、你的职责范围总结 ==========
doc.add_heading('五、你的职责范围总结', level=1)

p = doc.add_paragraph()
run = p.add_run('⭐ 你负责整个软件层（手机端 Android APP），具体包括：')
run.font.bold = True
run.font.color.rgb = RGBColor(0xFF, 0x57, 0x22)

# 你的职责表
your_table = doc.add_table(rows=1, cols=2)
your_table.style = 'Table Grid'
hdr = your_table.rows[0].cells
hdr[0].text = '模块分类'
hdr[1].text = '具体内容'

your_data = [
    ("表情渲染", "ExpressionCanvas 绘制、18种表情、眼内特效、4种风格、2种性别、动画过渡"),
    ("人脸视觉", "CameraX 摄像头、ML Kit 人脸检测、视线跟随、表情镜像、多人脸交互、休眠系统"),
    ("语音交互", "通义千问 ASR 实时识别、LLM 对话、Android TTS、打断机制、状态联动"),
    ("触摸交互", "边缘滑动调亮度音量、点击表情、电源长按开关机、刷新按钮、双指捏合切性别"),
    ("通信协议", "8899 TCP 表情控制客户端、8765 TCP 头部控制客户端、8900 UDP 感知上报、Protobuf 编解码"),
    ("系统状态", "电量映射、充电状态、错误等级、亮度管理、音量管理、IMU 姿态"),
    ("配置管理", "7大类60+参数热更新、增量同步、默认配置管理"),
    ("联调对接", "与嵌入式对齐 Protobuf 协议、联调测试、故障排查"),
]
for cat, content in your_data:
    row = your_table.add_row().cells
    row[0].text = cat
    row[1].text = content

doc.add_paragraph()

p = doc.add_paragraph()
run = p.add_run('不需要负责的部分：')
run.font.bold = True

not_your_items = [
    '嵌入式板端程序开发（RDK 主控服务端、电机驱动 PID）',
    '结构硬件设计（电机选型、机械结构、PCB 布线）',
    '硬件传感器驱动开发',
]
for item in not_your_items:
    doc.add_paragraph(item, style='List Bullet')

# 保存文档
output_path = r"F:\workspace\RPO系统总体架构文档.docx"
doc.save(output_path)
print(f"文档生成完成: {output_path}")
