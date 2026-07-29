import urllib.request
import sys
from docx import Document

# 下载文件
url = "https://aka.doubaocdn.com/s/bEwr1wqo6B"
save_path = r"F:\workspace\机器人表情系统规范_最新.docx"

try:
    urllib.request.urlretrieve(url, save_path)
    print(f"文件下载成功: {save_path}")
except Exception as e:
    print(f"下载失败: {e}")
    sys.exit(1)

# 读取文档
doc = Document(save_path)

# 打印所有段落和标题，了解结构
print("\n=== 文档结构 ===")
for i, para in enumerate(doc.paragraphs):
    if para.style.name.startswith('Heading'):
        print(f"[{para.style.name}] {para.text}")
    elif para.text.strip():
        print(f"  {para.text[:80]}...")

# 统计图片数量
print("\n=== 图片统计 ===")
image_count = 0
for rel in doc.part.rels.values():
    if "image" in rel.target_ref:
        image_count += 1
        print(f"图片 {image_count}: {rel.target_ref}")

print(f"\n总图片数: {image_count}")

# 保存图片
import os
img_dir = r"F:\workspace\doc_images"
os.makedirs(img_dir, exist_ok=True)

img_idx = 0
for rel in doc.part.rels.values():
    if "image" in rel.target_ref:
        img_idx += 1
        img_data = rel.target_part.blob
        ext = rel.target_ref.split('.')[-1]
        img_path = os.path.join(img_dir, f"image_{img_idx}.{ext}")
        with open(img_path, 'wb') as f:
            f.write(img_data)
        print(f"保存图片: {img_path}")
