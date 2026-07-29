from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN
import os

# 创建PPT
prs = Presentation()
prs.slide_width = Inches(13.333)
prs.slide_height = Inches(7.5)

# 颜色定义
DARK_BG = RGBColor(0x0A, 0x0A, 0x0F)
ACCENT_BLUE = RGBColor(0x4F, 0xC3, 0xF7)
ACCENT_PURPLE = RGBColor(0xCE, 0x93, 0xD8)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)
LIGHT_GRAY = RGBColor(0xBB, 0xBB, 0xBB)
MID_GRAY = RGBColor(0x88, 0x88, 0x88)

img1_path = r"F:\workspace\doc_images\image_1.jpeg"
img2_path = r"F:\workspace\doc_images\image_2.jpeg"

def set_slide_bg(slide, color):
    """设置幻灯片背景色"""
    background = slide.background
    fill = background.fill
    fill.solid()
    fill.fore_color.rgb = color

def add_title(slide, text, left, top, width, height, font_size=32, color=WHITE, bold=True):
    """添加标题文本"""
    txBox = slide.shapes.add_textbox(left, top, width, height)
    tf = txBox.text_frame
    tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = text
    p.font.size = Pt(font_size)
    p.font.color.rgb = color
    p.font.bold = bold
    p.alignment = PP_ALIGN.LEFT
    return txBox

def add_bullet_list(slide, items, left, top, width, height, font_size=16, color=LIGHT_GRAY):
    """添加项目符号列表"""
    txBox = slide.shapes.add_textbox(left, top, width, height)
    tf = txBox.text_frame
    tf.word_wrap = True
    
    for i, item in enumerate(items):
        if i == 0:
            p = tf.paragraphs[0]
        else:
            p = tf.add_paragraph()
        p.text = item
        p.font.size = Pt(font_size)
        p.font.color.rgb = color
        p.space_after = Pt(8)
        p.level = 0
    return txBox

def add_section_header(slide, text, left, top, color=ACCENT_BLUE):
    """添加小节标题"""
    txBox = slide.shapes.add_textbox(left, top, Inches(5), Inches(0.5))
    tf = txBox.text_frame
    p = tf.paragraphs[0]
    p.text = text
    p.font.size = Pt(20)
    p.font.color.rgb = color
    p.font.bold = True
    return txBox

# ========== 第1页：封面 ==========
slide1 = prs.slides.add_slide(prs.slide_layouts[6])  # 空白布局
set_slide_bg(slide1, DARK_BG)

# 主标题
title_box = slide1.shapes.add_textbox(Inches(1), Inches(2.5), Inches(11), Inches(1.5))
tf = title_box.text_frame
p = tf.paragraphs[0]
p.text = "机器人表情系统规范"
p.font.size = Pt(54)
p.font.color.rgb = WHITE
p.font.bold = True
p.alignment = PP_ALIGN.CENTER

# 副标题
subtitle_box = slide1.shapes.add_textbox(Inches(1), Inches(3.8), Inches(11), Inches(0.8))
tf2 = subtitle_box.text_frame
p2 = tf2.paragraphs[0]
p2.text = "纯眼部表情交互系统 · RPO Expression"
p2.font.size = Pt(22)
p2.font.color.rgb = ACCENT_BLUE
p2.alignment = PP_ALIGN.CENTER

# 底部分隔线
line_box = slide1.shapes.add_textbox(Inches(5), Inches(4.8), Inches(3.333), Inches(0.1))
tf3 = line_box.text_frame
p3 = tf3.paragraphs[0]
p3.text = "——————————"
p3.font.color.rgb = MID_GRAY
p3.alignment = PP_ALIGN.CENTER

# 底部说明
footer_box = slide1.shapes.add_textbox(Inches(1), Inches(5.2), Inches(11), Inches(0.6))
tf4 = footer_box.text_frame
p4 = tf4.paragraphs[0]
p4.text = "18种表情 · 5类眼内特效 · 人脸镜像交互 · 多链路通信"
p4.font.size = Pt(16)
p4.font.color.rgb = MID_GRAY
p4.alignment = PP_ALIGN.CENTER

# ========== 第2页：表情体系与视觉效果 ==========
slide2 = prs.slides.add_slide(prs.slide_layouts[6])
set_slide_bg(slide2, DARK_BG)

# 页面标题
add_title(slide2, "一、表情体系与视觉效果", Inches(0.6), Inches(0.3), Inches(7), Inches(0.7), font_size=28, color=WHITE)

# 左侧：表情优先级
add_section_header(slide2, "6级优先级体系", Inches(0.6), Inches(1.1), color=ACCENT_BLUE)
priority_items = [
    "Lv6 告警/升级：ALERT、UPDATING（最高）",
    "Lv5 说话：SPEAKING（TTS播报中）",
    "Lv4 情绪应答：HAPPY/SAD/ANGRY/SHY等",
    "Lv3 疑惑：CONFUSED",
    "Lv2 识别中：RECOGNIZING",
    "Lv1 基础状态：THINKING/LOW_BATTERY等",
    "Lv0 待机：IDLE/BLINK/SLEEPING（最低）",
]
add_bullet_list(slide2, priority_items, Inches(0.6), Inches(1.6), Inches(5.5), Inches(3.2), font_size=14)

# 左侧：眼内特效
add_section_header(slide2, "5类眼内特效", Inches(0.6), Inches(4.5), color=ACCENT_PURPLE)
effect_items = [
    "思考加载：双层反向旋转圆弧",
    "识别成功：8芒星+扩散光环",
    "开心星光：12粒子细碎闪烁",
    "错误脉冲：3环红色脉冲波纹",
    "严格限定瞳孔1.6倍半径范围内",
]
add_bullet_list(slide2, effect_items, Inches(0.6), Inches(5.0), Inches(5.5), Inches(2), font_size=14)

# 右侧：图片
if os.path.exists(img1_path):
    slide2.shapes.add_picture(img1_path, Inches(6.8), Inches(1.2), width=Inches(6))

# 图片说明
img_caption = slide2.shapes.add_textbox(Inches(6.8), Inches(5.5), Inches(6), Inches(0.4))
tf_cap = img_caption.text_frame
p_cap = tf_cap.paragraphs[0]
p_cap.text = "▲ 默认女款·杏眼风格（粉紫色光晕）"
p_cap.font.size = Pt(12)
p_cap.font.color.rgb = MID_GRAY
p_cap.alignment = PP_ALIGN.CENTER

# ========== 第3页：人脸交互与智能感知 ==========
slide3 = prs.slides.add_slide(prs.slide_layouts[6])
set_slide_bg(slide3, DARK_BG)

# 页面标题
add_title(slide3, "二、人脸交互与智能感知", Inches(0.6), Inches(0.3), Inches(7), Inches(0.7), font_size=28, color=WHITE)

# 左侧：图片
if os.path.exists(img2_path):
    slide3.shapes.add_picture(img2_path, Inches(0.5), Inches(1.2), width=Inches(6))

# 图片说明
img_caption2 = slide3.shapes.add_textbox(Inches(0.5), Inches(5.5), Inches(6), Inches(0.4))
tf_cap2 = img_caption2.text_frame
p_cap2 = tf_cap2.paragraphs[0]
p_cap2.text = "▲ 男款·丹凤眼风格（冰蓝色光晕）"
p_cap2.font.size = Pt(12)
p_cap2.font.color.rgb = MID_GRAY
p_cap2.alignment = PP_ALIGN.CENTER

# 右侧：表情镜像互动
add_section_header(slide3, "ML Kit 表情镜像互动", Inches(7), Inches(1.1), color=ACCENT_BLUE)
mirror_items = [
    "用户微笑 → 机器人开心（连续3帧≥0.7）",
    "用户眨眼 → 同步眨眼（80~500ms周期）",
    "用户惊讶 → 疑惑表情（眼睑突变检测）",
]
add_bullet_list(slide3, mirror_items, Inches(7), Inches(1.6), Inches(5.8), Inches(1.8), font_size=14)

# 右侧：多人脸交互
add_section_header(slide3, "多人脸交互逻辑", Inches(7), Inches(3.2), color=ACCENT_BLUE)
multi_items = [
    "1张人脸：视线精准跟随",
    "2张人脸：眼珠左右往复扫视",
    "≥3张人脸：环视+疑惑状态",
    "距离感知：走近瞳孔微缩，走远微扩",
]
add_bullet_list(slide3, multi_items, Inches(7), Inches(3.7), Inches(5.8), Inches(1.8), font_size=14)

# 右侧：休眠梦境
add_section_header(slide3, "休眠梦境系统", Inches(7), Inches(5.3), color=ACCENT_PURPLE)
sleep_items = [
    "无人脸9秒自动进入休眠",
    "5阶段状态机：清醒→打哈欠→浅眠→深眠→唤醒",
    "深眠REM快速眼动（4~8秒随机间隔）",
]
add_bullet_list(slide3, sleep_items, Inches(7), Inches(5.8), Inches(5.8), Inches(1.5), font_size=14)

# ========== 第4页：系统能力与操作方式 ==========
slide4 = prs.slides.add_slide(prs.slide_layouts[6])
set_slide_bg(slide4, DARK_BG)

# 页面标题
add_title(slide4, "三、系统能力与操作方式", Inches(0.6), Inches(0.3), Inches(8), Inches(0.7), font_size=28, color=WHITE)

# 第一列：通信协议
add_section_header(slide4, "通信协议", Inches(0.6), Inches(1.1), color=ACCENT_BLUE)
comm_items = [
    "TCP主通道：8899端口，JSON v2.0",
    "Protobuf多链路升级：效率提升84%",
    "TCP主 + UDP高频 + USB备 + BLE备",
    "故障切换 < 1秒，三级冗余",
    "心跳3秒，超时5秒自动重连",
]
add_bullet_list(slide4, comm_items, Inches(0.6), Inches(1.6), Inches(4), Inches(2.5), font_size=14)

# 第二列：渲染风格
add_section_header(slide4, "渲染风格", Inches(4.8), Inches(1.1), color=ACCENT_PURPLE)
style_items = [
    "4种渲染风格：卡通/赛博/极简/像素",
    "2种性别风格：男款丹凤眼 / 女款杏眼",
    "双指捏合一键切换，带过渡动画",
    "支持强度调节（0~100%）",
]
add_bullet_list(slide4, style_items, Inches(4.8), Inches(1.6), Inches(4), Inches(2), font_size=14)

# 第三列：操作方式
add_section_header(slide4, "操作方式", Inches(9), Inches(1.1), color=ACCENT_BLUE)
op_items = [
    "手柄控制：ABXY+肩键+十字键",
    "屏幕触摸：边缘滑调亮度/音量",
    "长按电源4秒开关机",
    "底部双击刷新重置",
    "TCP指令远程控制",
]
add_bullet_list(slide4, op_items, Inches(9), Inches(1.6), Inches(4), Inches(2), font_size=14)

# 下方：系统状态 + 配置化
# 左下方：系统状态
add_section_header(slide4, "系统状态映射", Inches(0.6), Inches(4.2), color=ACCENT_PURPLE)
status_items = [
    "电量6级：翠绿→金黄→橙黄→橙色→红色快闪",
    "充电状态：常规/快充/充满/异常",
    "错误5级：正常→提示→警告→严重→致命",
    "状态优先级：错误 > 升级 > 充电 > 电量 > 表情",
]
add_bullet_list(slide4, status_items, Inches(0.6), Inches(4.7), Inches(6), Inches(2), font_size=14)

# 右下方：参数热更新
add_section_header(slide4, "参数热更新", Inches(7.5), Inches(4.2), color=ACCENT_BLUE)
config_items = [
    "7大类60+参数实时下发",
    "人脸检测、睡眠、动画、颜色",
    "通信、渲染、系统状态阈值",
    "增量更新，无需重启APP",
]
add_bullet_list(slide4, config_items, Inches(7.5), Inches(4.7), Inches(5.5), Inches(2), font_size=14)

# 保存PPT
output_path = r"F:\workspace\机器人表情系统规范.pptx"
prs.save(output_path)
print(f"PPT生成完成: {output_path}")
print(f"共 {len(prs.slides)} 页")
