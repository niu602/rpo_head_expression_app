import urllib.request
import sys

# 下载文件
url = "https://aka.doubaocdn.com/s/0uFd1wqnph"
save_path = r"F:\workspace\机器人表情系统规范.docx"

try:
    urllib.request.urlretrieve(url, save_path)
    print(f"文件下载成功: {save_path}")
except Exception as e:
    print(f"下载失败: {e}")
    sys.exit(1)

# 检查python-docx
try:
    from docx import Document
    from docx.shared import Pt, RGBColor
    from docx.enum.text import WD_ALIGN_PARAGRAPH
    print("python-docx 可用")
except ImportError:
    print("安装 python-docx...")
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "python-docx"])
    from docx import Document
    from docx.shared import Pt, RGBColor
    from docx.enum.text import WD_ALIGN_PARAGRAPH
    print("python-docx 安装完成")

# 打开文档
doc = Document(save_path)

# 添加第十章：屏幕触摸交互规则
doc.add_heading("十、屏幕触摸交互规则", level=1)

# 10.1 触摸分区总览
doc.add_heading("10.1 触摸分区总览", level=2)
p = doc.add_paragraph()
p.add_run("屏幕采用分区触摸设计，横屏模式下分为左边缘、中间交互区、右边缘三大竖区，顶部为电源区、底部为刷新区：")

# 分区表格
table = doc.add_table(rows=1, cols=3)
table.style = 'Table Grid'
hdr_cells = table.rows[0].cells
hdr_cells[0].text = '区域'
hdr_cells[1].text = '位置'
hdr_cells[2].text = '功能'

zones = [
    ("左边缘区", "屏幕左侧 15% 宽度", "上下滑动调节亮度"),
    ("中间交互区", "屏幕中间 70% 宽度", "点击/长按/双指捏合交互"),
    ("右边缘区", "屏幕右侧 15% 宽度", "上下滑动调节音量"),
    ("顶部电源区", "屏幕顶部 20% 高度", "长按4秒开关机"),
    ("底部刷新区", "屏幕底部 20% 高度", "双击刷新重置"),
]

for zone, pos, func in zones:
    row_cells = table.add_row().cells
    row_cells[0].text = zone
    row_cells[1].text = pos
    row_cells[2].text = func

# 10.2 边缘滑动调节
doc.add_heading("10.2 边缘滑动调节", level=2)

doc.add_heading("亮度调节（左边缘）", level=3)
p = doc.add_paragraph()
p.add_run("• 触摸左边缘 15% 区域，上下滑动调节屏幕亮度\n")
p.add_run("• 向上滑动增加亮度，向下滑动降低亮度\n")
p.add_run("• 调节范围：最低 10% ~ 最高 100%（关机状态支持到 3% 微光）\n")
p.add_run("• 滑动过程中实时生效，松手后保持当前亮度")

doc.add_heading("音量调节（右边缘）", level=3)
p = doc.add_paragraph()
p.add_run("• 触摸右边缘 15% 区域，上下滑动调节系统音量\n")
p.add_run("• 向上滑动增加音量，向下滑动降低音量\n")
p.add_run("• 调节范围：0% ~ 100%，系统媒体音量同步变化")

# 10.3 中间区域点击交互
doc.add_heading("10.3 中间区域点击交互", level=2)
p = doc.add_paragraph()
p.add_run("中间 70% 区域为表情交互区，单击不同位置触发不同表情反馈：")

table2 = doc.add_table(rows=1, cols=3)
table2.style = 'Table Grid'
hdr_cells = table2.rows[0].cells
hdr_cells[0].text = '点击位置'
hdr_cells[1].text = '触发表情'
hdr_cells[2].text = '说明'

clicks = [
    ("眉心区域", "CONFUSED 疑惑", "单击眉心位置，眼睛呈现疑惑神态"),
    ("左眼区域", "瞳孔向右躲闪", "点击左眼，瞳孔向右快速躲闪后回正"),
    ("右眼区域", "瞳孔向左躲闪", "点击右眼，瞳孔向左快速躲闪后回正"),
    ("脸颊区域", "SHY 害羞", "点击脸颊两侧，触发害羞表情"),
]

for pos, expr, desc in clicks:
    row_cells = table2.add_row().cells
    row_cells[0].text = pos
    row_cells[1].text = expr
    row_cells[2].text = desc

# 10.4 电源按钮（顶部）
doc.add_heading("10.4 电源按钮（顶部）", level=2)

doc.add_heading("开关机操作", level=3)
p = doc.add_paragraph()
p.add_run("• 位置：屏幕顶部中央（额头区域）\n")
p.add_run("• 操作：长按顶部 20% 区域持续 4 秒，触发关机 / 开机切换\n")
p.add_run("• 视觉反馈：长按过程中显示环形进度条，进度满后执行开关机\n")
p.add_run("• 关机动画：眼睛缓慢闭合 + 屏幕亮度渐暗到微光，时长约 0.8 秒\n")
p.add_run("• 开机动画：屏幕亮度渐亮 + 眼睛缓慢睁开 + 自动眨眼一次，时长约 0.6 秒")

doc.add_heading("关机状态呼吸灯", level=3)
p = doc.add_paragraph()
p.add_run("• 关机后屏幕保持极微光（3% 亮度），顶部电源按钮以低亮度缓慢呼吸闪烁\n")
p.add_run("• 呼吸周期约 2.4 秒，透明度在 11% ~ 33% 之间循环\n")
p.add_run("• 作用：提示用户开机按钮位置，避免全黑找不到按键\n")
p.add_run("• 关机状态下其他触摸区域不响应，仅电源区长按有效")

# 10.5 刷新按钮（底部）
doc.add_heading("10.5 刷新按钮（底部）", level=2)
p = doc.add_paragraph()
p.add_run("• 位置：屏幕底部中央，与顶部电源按钮上下对称\n")
p.add_run("• 图标：环形箭头刷新图标，默认半透明呼吸显示")

doc.add_heading("双击确认机制", level=3)
p = doc.add_paragraph()
p.add_run("采用「双击确认」防误触设计：\n")
p.add_run("1. 第一次点击：启动 1 秒倒计时，按钮高亮，环形进度条倒计时\n")
p.add_run("2. 倒计时内第二次点击：确认执行刷新，立即生效\n")
p.add_run("3. 超时未点第二次：自动取消，进入 2 秒冷却期")

doc.add_heading("刷新效果", level=3)
p = doc.add_paragraph()
p.add_run("• 立即停止 Demo 演示序列\n")
p.add_run("• 软重置表情状态，回到待机 IDLE\n")
p.add_run("• 视觉反馈：快速眨眼 + 应答表情闪一下，明确感知操作生效\n")
p.add_run("• 冷却时间：刷新后 1.5 秒冷却期，按钮变灰不可点击")

# 10.6 双指捏合切换性别
doc.add_heading("10.6 双指捏合切换性别", level=2)
p = doc.add_paragraph()
p.add_run("• 操作：在双眼区域双指捏合（缩小手势），切换男/女风格\n")
p.add_run("• 女款杏眼（默认）：圆润饱满，暖白眼白，粉紫色光晕\n")
p.add_run("• 男款丹凤眼：细长眼尾上挑，冷白眼白，冰蓝色光晕")

doc.add_heading("过渡动画", level=3)
p = doc.add_paragraph()
p.add_run("切换过程带三段式过渡动画，避免生硬跳变：\n")
p.add_run("1. 收缩：眼睛先向中间收窄\n")
p.add_run("2. 变形：眼型在中间态完成切换\n")
p.add_run("3. 展开：新眼型缓慢展开到正常大小")

# 10.7 触摸优先级
doc.add_heading("10.7 触摸优先级", level=2)
p = doc.add_paragraph()
p.add_run("多种手势同时触发时，按以下优先级响应：\n")
p.add_run("1. 电源长按（最高优先级，4秒硬触发）\n")
p.add_run("2. 刷新按钮点击\n")
p.add_run("3. 边缘滑动（亮度/音量）\n")
p.add_run("4. 双指捏合（性别切换）\n")
p.add_run("5. 单指点按（表情交互）")

# 保存文档
doc.save(save_path)
print(f"文档更新完成: {save_path}")
