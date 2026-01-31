import sqlite3
import os
import glob

# 配置路径
ASSETS_DIR = "android/app/src/main/assets"
DICTS_DIR = os.path.join(ASSETS_DIR, "dicts")
OUTPUT_DB = os.path.join(ASSETS_DIR, "dictionary.db")

def try_decode(bytes_data):
    encodings = ['utf-8', 'utf-16', 'gb18030', 'utf-16-le', 'utf-16-be']
    for enc in encodings:
        try:
            return bytes_data.decode(enc), enc
        except Exception:
            continue
    return None, None

def build_db():
    print(f"🔥 开始构建数据库 (v4 含 word_len/syllables): {OUTPUT_DB}")

    if os.path.exists(OUTPUT_DB):
        os.remove(OUTPUT_DB)

    conn = sqlite3.connect(OUTPUT_DB)
    cursor = conn.cursor()

    cursor.execute('''
        CREATE TABLE words (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            input TEXT,
            acronym TEXT,
            t9 TEXT,
            word TEXT,
            freq INTEGER,
            lang INTEGER,
            word_len INTEGER,
            syllables INTEGER
        )
    ''')

    t9_map = str.maketrans('abcdefghijklmnopqrstuvwxyz', '22233344455566677778889999')

    txt_files = glob.glob(os.path.join(DICTS_DIR, "*.txt"))
    if not txt_files:
        print("❌ 错误：没有找到 txt 词库文件！")
        return

    total_count = 0
    check_words = {"一个": False, "你好": False, "good": False}

    for txt_file in txt_files:
        filename = os.path.basename(txt_file)
        print(f"正在处理: {filename} ...")

        is_chinese = filename.startswith("cn_")
        is_base = ("base" in filename) or ("common" in filename)
        boost = 100000000 if is_base else 0
        lang_code = 0 if is_chinese else 1

        batch_data = []

        with open(txt_file, 'rb') as f:
            content_bytes = f.read()
            content_str, _ = try_decode(content_bytes)
            if not content_str:
                continue

            lines = content_str.splitlines()
            for line in lines:
                line = line.strip()
                if not line:
                    continue
                if line.startswith('\ufeff'):
                    line = line[1:]

                parts = line.split()

                try:
                    input_code = ""
                    acronym = ""
                    t9_code = ""
                    word = ""
                    freq = 50
                    word_len = 0
                    syllables = 0

                    if is_chinese and len(parts) >= 2:
                        raw_pinyin = parts[0].lower()
                        input_code = raw_pinyin.replace("'", "")
                        word = parts[1]
                        if len(parts) >= 3 and parts[2].isdigit():
                            freq = int(parts[2])

                        # 精确候选分桶用
                        word_len = len(word)
                        syllables = len([s for s in raw_pinyin.split("'") if s])

                        if "'" in raw_pinyin:
                            acronym = "".join([s[0] for s in raw_pinyin.split("'") if s])
                        else:
                            acronym = input_code

                        t9_code = input_code.translate(t9_map)

                    elif (not is_chinese) and len(parts) >= 3:
                        input_code = parts[0].lower()
                        word = parts[2]
                        if len(parts) >= 4 and parts[3].isdigit():
                            freq = int(parts[3])

                        acronym = input_code
                        t9_code = input_code.translate(t9_map)

                        word_len = len(word)
                        syllables = 0

                    else:
                        continue

                    if word.isdigit():
                        continue
                    if (not is_chinese) and (not word[0].isalpha()):
                        continue

                    if word in check_words:
                        check_words[word] = True

                    batch_data.append((input_code, acronym, t9_code, word, freq + boost, lang_code, word_len, syllables))

                except Exception:
                    continue

        if batch_data:
            cursor.executemany(
                'INSERT INTO words (input, acronym, t9, word, freq, lang, word_len, syllables) VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
                batch_data
            )
            total_count += len(batch_data)
            conn.commit()

    print("正在建立索引...")
    cursor.execute('CREATE INDEX IF NOT EXISTS index_input ON words(input)')
    cursor.execute('CREATE INDEX IF NOT EXISTS index_acronym ON words(acronym)')
    cursor.execute('CREATE INDEX IF NOT EXISTS index_t9 ON words(t9)')
    cursor.execute('CREATE INDEX IF NOT EXISTS index_lang ON words(lang)')
    cursor.execute('CREATE INDEX IF NOT EXISTS index_word_len ON words(word_len)')
    cursor.execute('CREATE INDEX IF NOT EXISTS index_syllables ON words(syllables)')
    conn.commit()

    conn.close()

    print(f"✅ 完成！共导入 {total_count} 条。")
    for w, found in check_words.items():
        print(f"  检查 '{w}': {found}")

if __name__ == "__main__":
    build_db()