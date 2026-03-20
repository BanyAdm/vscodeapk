content = open('.github/workflows/build.yml').read()
old = """      - name: Remove termux library dependency
        run: sed -i \"s|implementation fileTree(dir: 'libs', include: \\['\\.aar', '\\*.jar'\\])||g\" app/build.gradle"""
new = """      - name: Remove termux library dependency
        run: python3 -c "data=open('app/build.gradle').read(); open('app/build.gradle','w').write('\\n'.join(l for l in data.splitlines() if 'fileTree' not in l))" """
open('.github/workflows/build.yml','w').write(content.replace(old,new))
print("done")
