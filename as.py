import os
import re

# 配置：你的 UI 包路径
SEARCH_DIR = "app/src/main/java/org/mz/mzdkplayer/ui"
OUTPUT_FILE = "strings_draft_v2.xml"

# 1. 匹配中文字符串的正则
CHINESE_STR_REGEX = re.compile(r'"([^"]*[\u4e00-\u9fa5]+[^"]*)"')

# 2. 判定是否为日志行的正则 (包含 Log, println, Timber 等)
LOG_PREFIX_REGEX = re.compile(r'^\s*(Log\.[viewd]|println|Timber\.[viewd])\(', re.IGNORECASE)

def extract_clean_chinese():
    results = {}

    for root, dirs, files in os.walk(SEARCH_DIR):
        for file in files:
            if file.endswith(".kt"):
                file_path = os.path.join(root, file)
                with open(file_path, 'r', encoding='utf-8') as f:
                    content = f.read()

                    # --- 步骤 A: 去除多行注释 /* ... */ ---
                    content = re.sub(r'/\*.*?\*/', '', content, flags=re.DOTALL)

                    # 逐行处理
                    lines = content.split('\n')
                    for line in lines:
                        # --- 步骤 B: 去除单行注释 // ---
                        line = line.split('//')[0]

                        # --- 步骤 C: 排除日志行 ---
                        if LOG_PREFIX_REGEX.search(line):
                            continue

                        # --- 步骤 D: 提取中文 ---
                        matches = CHINESE_STR_REGEX.findall(line)
                        for match in matches:
                            clean_text = match.strip()
                            if clean_text and clean_text not in results.values():
                                # 生成唯一的 key，这里简单用序号，你可以手动改得更有意义
                                res_id = f"ui_label_{len(results) + 1}"
                                results[res_id] = clean_text

    # 写入文件
    with open(OUTPUT_FILE, 'w', encoding='utf-8') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n')
        f.write('<resources>\n')
        for res_id, text in results.items():
            # 处理 XML 特殊字符转义
            escaped_text = text.replace("'", "\\'").replace('"', '\\"')
            f.write(f'    <string name="{res_id}">{escaped_text}</string>\n')
        f.write('</resources>')

    print(f"🚀 深度扫描完成！")
    print(f"排除范围：注释 (//, /* */)、日志 (Log, println, Timber)")
    print(f"共提取到独特中文词条: {len(results)} 条")
    print(f"结果文件: {OUTPUT_FILE}")

if __name__ == "__main__":
    extract_clean_chinese()