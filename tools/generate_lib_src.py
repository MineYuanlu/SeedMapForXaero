# -*- coding: utf-8 -*-

import os
import glob
import zipfile
import subprocess
import requests

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FERNFLOWER_JAR = os.path.join(ROOT_DIR, 'refs', 'tools', 'fernflower.jar')
DST_DIR = os.path.join(ROOT_DIR, 'refs','lib_src')
CACHE_DIR = os.path.join(DST_DIR, '.cache')

# 26.1.2 线各依赖的最新版本（chocolateminecraft.com/maven 上查询）
XAERO_MAP_VERSION = '1.44.2'
XAERO_MINIMAP_VERSION = '26.4.2'
XAERO_LIB_VERSION = '1.7.1'
MAVEN_REPO = 'https://chocolateminecraft.com/maven'

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
    提取依赖的源码，优先使用 -sources.jar，否则反编译。

    优先从 Gradle 缓存中找 JAR；缓存缺失时自动从 chocolateminecraft.com/maven
    下载到 refs/lib_src/.cache。

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

    # 4. Gradle 缓存缺失 → 联网下载到本地缓存目录
    if not target_jar:
        print(f"Gradle 缓存中未找到 {dep}，尝试从 {MAVEN_REPO} 下载…")
        cache_group = os.path.join(CACHE_DIR, *group.split('.'), artifact, version)
        os.makedirs(cache_group, exist_ok=True)
        base_url = f"{MAVEN_REPO}/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}"
        sources_jar, normal_jar = download_jar(base_url, cache_group, artifact, version,
                                               sources_jar, normal_jar)
        target_jar = sources_jar or normal_jar
        if not target_jar:
            raise FileNotFoundError(f"无法下载依赖 {dep}，URL: {base_url}")

    # 5. 创建输出目录
    os.makedirs(out_dir, exist_ok=True)

    # 6. 处理源码
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

def download_jar(base_url, cache_group, artifact, version, sources_jar, normal_jar):
    """从 maven 仓库下载 JAR 到本地缓存目录，返回更新后的 (sources_jar, normal_jar)。"""
    for suffix, existing in (("-sources.jar", sources_jar), (".jar", normal_jar)):
        if existing:
            continue
        cached_path = os.path.join(cache_group, f"{artifact}-{version}{suffix}")
        if os.path.isfile(cached_path):
            (sources_jar, normal_jar) = (cached_path, normal_jar) if suffix == "-sources.jar" else (sources_jar, cached_path)
            continue
        try:
            response = requests.get(f"{base_url}{suffix}", timeout=60)
            response.raise_for_status()
        except requests.RequestException:
            continue
        with open(cached_path, 'wb') as f:
            f.write(response.content)
        if suffix == "-sources.jar":
            sources_jar = cached_path
        else:
            normal_jar = cached_path
        print(f"✅ 已下载 {artifact}-{version}{suffix}")
    return sources_jar, normal_jar

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
    unpack_dep(f"xaero.map:xaeroworldmap-fabric-26.1.2:{XAERO_MAP_VERSION}",os.path.join(DST_DIR,'xaeroworldmap'))
    unpack_dep(f"xaero.minimap:xaerominimap-fabric-26.1.2:{XAERO_MINIMAP_VERSION}",os.path.join(DST_DIR,'xaerominimap'))
    unpack_dep(f"xaero.lib:xaerolib-fabric-26.1.2:{XAERO_LIB_VERSION}",os.path.join(DST_DIR,'xaerolib'))
if __name__ == "__main__":
    main()