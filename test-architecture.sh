#!/bin/bash

# 1. 아키텍처 추출 실행
echo "🚀 Generating PlantUML..."
./gradlew generateClassDiagrams

# 2. 파이썬 주입 로직 실행 (워크플로우와 동일)
echo "📝 Injecting puml into README.md..."
python3 -c '
import re

# 파일 경로 (워크플로우와 동일하게 설정)
readme_path = "README.md"
puml_path = "docs/architecture/plantuml/full-architecture.puml"

with open(readme_path, "r", encoding="utf-8") as f:
    readme = f.read()
    
with open(puml_path, "r", encoding="utf-8") as f:
    puml = f.read()
    
puml_block = f"\n<details><summary><b>[클릭하여 전체 아키텍처 다이어그램 보기]</b></summary>\n\n\`\`\`plantuml\n{puml}\`\`\`\n</details>\n"

# 마커 사이를 치환 (마커도 블록에 포함시켜 갱신)
pattern = r"<!-- START_PUML -->.*?<!-- END_PUML -->"
new_readme = re.sub(pattern, puml_block, readme, flags=re.DOTALL)

with open(readme_path, "w", encoding="utf-8") as f:
    f.write(new_readme)
'

# 3. 결과 확인
if [ $? -eq 0 ]; then
    echo "✅ 테스트 완료: README.md가 갱신되었습니다."
    echo "🔍 변경사항을 확인하려면: git diff README.md"
else
    echo "❌ 에러: 파이썬 스크립트 실행 중 문제가 발생했습니다."
fi