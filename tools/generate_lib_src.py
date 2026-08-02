# -*- coding: utf-8 -*-

import os
import sys
import glob
import zipfile
import subprocess
import configparser
import requests

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FERNFLOWER_JAR = os.path.join(ROOT_DIR, 'refs', 'tools', 'fernflower.jar')
DST_DIR = os.path.join(ROOT_DIR, 'refs','lib_src')

def clone_xpple_cubiomes():
    URL="https://github.com/xpple/cubiomes.git"
    DIR=os.path.join(DST_DIR,'xpple-cubiomes')
    if os.path.exists(DIR):
        print(f"已存在 {DIR}，跳过克隆。")
        return
    subprocess.run(['git', 'clone', URL, DIR], check=True)
    print(f"✅ 已克隆 {URL} 到 {DIR}")


def unpack_dep(dep: str, out_dir: str):
    """
    从 Gradle 缓存中提取依赖的源码，优先使用 -sources.jar，否则反编译。

    Args:
        dep: 依赖坐标，格式 "group:artifact:version"
        out_dir: 输出目录（存放 .java 源码）

    Raises:
        FileNotFoundError: 找不到 JAR 或反编译器
        subprocess.CalledProcessError: 反编译失败
    """
    if os.path.exists(out_dir) and os.listdir(out_dir):
        print(f"已存在 {out_dir}，跳过提取。")
        return
    # 1. 解析坐标
    parts = dep.split(':')
    if len(parts) != 3:
        raise ValueError(f"依赖格式错误，应为 'group:artifact:version'，实际为 {dep}")
    group, artifact, version = parts

    # 2. 在 Gradle 缓存中搜索 JAR
    cache_root = os.path.expanduser("~/.gradle/caches")
    # 缓存结构: modules-2/files-2.1/<group>/<artifact>/<version>/<sha1>/<artifact>-<version>*.jar
    search_pattern = os.path.join(
        cache_root, "modules-2", "files-2.1",
        group, artifact, version,
        "*", f"{artifact}-{version}*.jar"
    )
    matches = glob.glob(search_pattern)

    if not matches:
        raise FileNotFoundError(f"在 {cache_root} 中未找到依赖 {dep} 的任何 JAR 文件")

    # 3. 优先选择 -sources.jar，否则取第一个普通 JAR（排除 sources）
    sources_jar = None
    normal_jar = None
    for f in matches:
        if f.endswith("-sources.jar"):
            sources_jar = f
            break
        elif f.endswith(".jar") and not f.endswith("-sources.jar"):
            normal_jar = f
    # 如果没找到 sources，就用普通 jar
    target_jar = sources_jar or normal_jar
    if not target_jar:
        raise FileNotFoundError(f"找不到有效的 JAR 文件（匹配项: {matches})")

    # 4. 创建输出目录
    os.makedirs(out_dir, exist_ok=True)

    # 5. 处理源码
    if sources_jar:
        # 有 -sources.jar → 直接解压
        with zipfile.ZipFile(sources_jar, 'r') as zf:
            zf.extractall(out_dir)
        print(f"✅ 已从 {sources_jar} 解压源码到 {out_dir}")
    else:
        # 没有 sources → 使用 FernFlower 反编译
        fernflower_jar = os.environ.get('FERNFLOWER_JAR', 'fernflower.jar')
        if not os.path.isfile(fernflower_jar):
            download_fernflower()
            fernflower_jar = FERNFLOWER_JAR
        if not os.path.isfile(fernflower_jar):
            raise FileNotFoundError(
                f"未找到 FernFlower 反编译器（{fernflower_jar}）。\n"
                "请设置环境变量 FERNFLOWER_JAR 指向 fernflower.jar，"
                "或将 fernflower.jar 放在当前工作目录。"
            )
        # 反编译命令：java -jar fernflower.jar <输入jar> <输出目录>
        cmd = ['java', '-jar', fernflower_jar, target_jar, out_dir]
        subprocess.run(cmd, check=True)

        # 检测输出是否只有 f"{artifact}-{version}*.jar", 如果是则unzip
        outputs = os.listdir(out_dir)
        if len(outputs) == 1 and outputs[0].endswith(".jar"):
            java_jar = os.path.join(out_dir, outputs[0])
            with zipfile.ZipFile(java_jar, 'r') as zf:
                zf.extractall(out_dir)
                print(f"✅ 已从 {target_jar} 解压源码到 {out_dir}")
            os.remove(java_jar)


        print(f"✅ 已反编译 {target_jar} 到 {out_dir}")

def get_gradle_properties():
    config = configparser.ConfigParser()
    with open(os.path.join(ROOT_DIR, 'gradle.properties'), 'r') as f:
        config.read_string('[root]\n' + f.read())
    return dict(config['root'])

def download_fernflower():
    if os.path.isfile(FERNFLOWER_JAR):
        return
    url = "https://jitpack.io/com/github/JetBrains/fernflower/master/fernflower-master.jar"
    response = requests.get(url)
    os.makedirs(os.path.dirname(FERNFLOWER_JAR), exist_ok=True)
    with open(FERNFLOWER_JAR, 'wb') as f:
        f.write(response.content)
    print(f"✅ 已下载 FernFlower 反编译器到 {FERNFLOWER_JAR}")



def main():
    clone_xpple_cubiomes()
    config = get_gradle_properties()
    xpple_cubiomes_version=config['xpple_cubiomes_version']
    xaero_map_version=config['xaeroMapVersion']
    xaero_lib_version=config['xaeroLibVersion']
    unpack_dep(f"dev.xpple:cubiomes:{xpple_cubiomes_version}",os.path.join(DST_DIR,'cubiomes'))
    unpack_dep(f"xaero.map:xaeroworldmap-fabric-26.1.2:{xaero_map_version}",os.path.join(DST_DIR,'xaeroworldmap'))
    unpack_dep(f"xaero.lib:xaerolib-fabric-26.1.2:{xaero_lib_version}",os.path.join(DST_DIR,'xaerolib'))
if __name__ == "__main__":
    main()