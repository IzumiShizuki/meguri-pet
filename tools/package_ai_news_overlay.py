"""Package only AI-news classes over an existing Core JAR, preserving unrelated deployment work."""
from __future__ import annotations
import argparse
import hashlib
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def create_overlay(base: Path, classes: Path, output: Path) -> dict[str, bytes]:
    replacements = {}
    patterns = (
        'com/meguri/core/daily/AiNews*.class',
        'com/meguri/core/daily/JuyaRssReader*.class',
        'com/meguri/core/daily/DailyReportStore.class',
        'com/meguri/core/llm/LlmProvider.class',
        'com/meguri/core/llm/LangChain4jLlmProvider*.class',
        'com/meguri/core/llm/GlobalPromptBudget.class',
    )
    for pattern in patterns:
        for path in classes.glob(pattern):
            replacements['BOOT-INF/classes/' + path.relative_to(classes).as_posix()] = path.read_bytes()
    if len(replacements) < 10:
        raise RuntimeError('Compiled news overlay is incomplete')
    with zipfile.ZipFile(base) as original:
        config = original.read('BOOT-INF/classes/application.yml').decode('utf-8')
        expected = 'import: optional:classpath:application-notion.yml'
        if expected not in config:
            raise RuntimeError('Existing Core configuration import differs; inspect before patching')
        config = config.replace(expected, expected + ',optional:file:${MEGURI_DAILY_REPORT_DIR:${user.home}/.meguri/daily-reports}/ai-news.properties', 1)
        replacements['BOOT-INF/classes/application.yml'] = config.encode('utf-8')
        output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output, 'w') as patched:
            for item in original.infolist():
                patched.writestr(item, replacements.pop(item.filename, original.read(item)))
            for name, content in replacements.items():
                patched.writestr(name, content, compress_type=zipfile.ZIP_DEFLATED)
    # Return all selected files for small remote jar-update transfer, using the completed output.
    with zipfile.ZipFile(output) as patched:
        names = [name for name in patched.namelist() if name == 'BOOT-INF/classes/application.yml'
                 or any(name == 'BOOT-INF/classes/' + p.relative_to(classes).as_posix()
                        for pattern in patterns for p in classes.glob(pattern))]
        return {name: patched.read(name) for name in names}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-jar', required=True, type=Path)
    parser.add_argument('--classes-directory', type=Path, default=ROOT / 'java/meguri-core/target/classes')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    files = create_overlay(args.base_jar, args.classes_directory, args.output)
    print({'overlay_files': len(files), 'transfer_bytes': sum(map(len, files.values())),
           'output_sha256': hashlib.sha256(args.output.read_bytes()).hexdigest()})

if __name__ == '__main__': main()
