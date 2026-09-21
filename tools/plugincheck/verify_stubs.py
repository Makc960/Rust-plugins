#!/usr/bin/env python3
"""Сверяет заглушки API с дампом исходников игры.

Заглушки пишутся вручную, и ошибка в них не видна компилятору: плагин
собирается локально и падает уже на сервере. Скрипт ловит два вида расхождений:

  * тип объявлен не в том namespace (так "DamageTypeList" уехал из Rust
    в глобальный и плагин не собрался на сервере);
  * у типа объявлен член, которого в игре нет (так появился
    "displayName_english", которого не существует вовсе).

Запуск:  python3 verify_stubs.py <каталог-дампа> [каталог-заглушек]
"""

import os
import re
import sys

TYPE_RE = re.compile(
    r'^\s*(?:public|internal)\s+(?:static\s+|sealed\s+|abstract\s+|partial\s+|readonly\s+)*'
    r'(class|struct|enum|interface)\s+(\w+)\s*(?::\s*([^{]+))?')
MEMBER_RE = re.compile(
    r'^\s*public\s+(?:static\s+|virtual\s+|override\s+|readonly\s+|const\s+|abstract\s+|new\s+)*'
    r'(?:[\w<>\[\]\.,\? ]+?)\s+(\w+)\s*(?:[;=({]|=>)')
# Свойство, у которого тело начинается со следующей строки: "public float condition".
PROPERTY_RE = re.compile(
    r'^\s*public\s+(?:static\s+|virtual\s+|override\s+|abstract\s+|new\s+)*'
    r'(?:[\w<>\[\]\.,\? ]+?)\s+(\w+)\s*$')
ENUM_MEMBER_RE = re.compile(r'^\s*(\w+)\s*(?:=\s*-?\w+\s*)?,?\s*$')

STRING_RE = re.compile(r'"(?:\\.|[^"\\])*"')
CHAR_RE = re.compile(r"'(?:\\.|[^'\\])*'")
COMMENT_RE = re.compile(r'//.*$')


def strip_literals(line):
    """Убирает строки, символы и комментарии.

    Без этого фигурные скобки внутри литералов вроде "{0}" сбивают подсчёт
    вложенности, и типы приписываются не тому namespace.
    """
    line = STRING_RE.sub('""', line)
    line = CHAR_RE.sub("''", line)
    return COMMENT_RE.sub('', line)


def scan(path):
    """{тип: (namespace, {члены})} по одному файлу."""
    found = {}
    ns_stack = []
    depth = 0
    pending_ns = None
    type_stack = []          # (имя, глубина, это_enum)

    with open(path, encoding='utf-8', errors='replace') as handle:
        for line in handle:
            stripped = line.strip()

            ns_match = re.match(r'^\s*namespace\s+([\w.]+)', line)
            if ns_match:
                pending_ns = ns_match.group(1)

            type_match = TYPE_RE.match(line)
            if type_match and not stripped.endswith(';'):
                kind, name = type_match.group(1), type_match.group(2)
                bases = type_match.group(3) or ''
                namespace = ns_stack[-1][0] if ns_stack else ''
                if name not in found:
                    found[name] = [namespace, set(), set()]
                for base in bases.split(','):
                    base = base.strip().split('<')[0].split('.')[-1]
                    if base:
                        found[name][2].add(base)
                type_stack.append((name, depth, kind == 'enum'))
            elif type_stack:
                name, _, is_enum = type_stack[-1]
                if is_enum:
                    m = ENUM_MEMBER_RE.match(stripped)
                    if m and m.group(1) not in ('', 'public'):
                        found[name][1].add(m.group(1))
                else:
                    m = MEMBER_RE.match(line) or PROPERTY_RE.match(line)
                    if m:
                        found[name][1].add(m.group(1))

            for ch in strip_literals(line):
                if ch == '{':
                    depth += 1
                    if pending_ns is not None:
                        parent = ns_stack[-1][0] if ns_stack else ''
                        full = parent + '.' + pending_ns if parent else pending_ns
                        ns_stack.append((full, depth))
                        pending_ns = None
                elif ch == '}':
                    if ns_stack and ns_stack[-1][1] == depth:
                        ns_stack.pop()
                    depth -= 1
                    # Выталкиваем закрывшиеся типы уже после уменьшения глубины,
                    # иначе вложенный enum остаётся на вершине стека и поля
                    # внешнего класса уходят в него.
                    while type_stack and type_stack[-1][1] >= depth:
                        type_stack.pop()
    return found


def scan_tree(directory):
    merged = {}
    for entry in sorted(os.listdir(directory)):
        if not entry.endswith('.cs'):
            continue
        for name, (namespace, members, bases) in scan(os.path.join(directory, entry)).items():
            if name in merged:
                merged[name][0].add(namespace)
                merged[name][1].update(members)
                merged[name][2].update(bases)
            else:
                merged[name] = [{namespace}, set(members), set(bases)]
    return merged


def members_with_inherited(index, name, seen=None):
    """Члены типа вместе с унаследованными от базовых классов."""
    if seen is None:
        seen = set()
    if name in seen or name not in index:
        return set()
    seen.add(name)

    _namespaces, members, bases = index[name]
    result = set(members)
    for base in bases:
        result |= members_with_inherited(index, base, seen)
    return result


# Известные расхождения, которые не являются ошибкой.
# Либо это наши собственные вспомогательные типы, либо дамп декомпилирован так,
# что тип потерял namespace, либо член лежит во вложенном типе и сканер его
# не видит. Список намеренно короткий: всё остальное должно чиниться.
ALLOWED_NAMESPACE = {
    'GameObjectEx',            # наш extension-класс, имя совпало со служебным
    'NetworkableId',           # в дампе потерял namespace Network
    'TextAnchor',              # UnityEngine.CoreModule декомпилирован без namespace
    'Server',                  # имя встречается в пяти разных namespace
    'PluginReferenceAttribute',
}

ALLOWED_MEMBERS = {
    'CuiHelper.LastElementCount',   # наши счётчики для замеров
    'CuiHelper.LastJsonLength',
    'Arg.Player',                   # вложенный ConsoleSystem.Arg
    'Timer.Every',                  # вложенный Oxide.Core.Libraries.Timer
    'EncryptedValue.T',             # параметр обобщённого типа
    'Vector3.magnitude',            # свойство структуры Unity
    'PooledList.Dispose',           # через BasePooledList<T,TSelf> : IDisposable
    'ServerMgr.StartCoroutine',     # унаследовано от MonoBehaviour (UnityEngine.CoreModule.cs:73654)
    'ServerMgr.StopCoroutine',
}


def main():
    dump = sys.argv[1]
    stubs = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(__file__), 'stubs')

    game = scan_tree(dump)
    mine = scan_tree(stubs)

    ns_problems = []
    member_problems = []
    unknown = []

    for name, (namespaces, members, _bases) in sorted(mine.items()):
        namespace = sorted(namespaces)[0]
        if name not in game:
            unknown.append(name)
            continue

        real_namespaces = game[name][0]
        real_members = members_with_inherited(game, name)
        if namespace not in real_namespaces and name not in ALLOWED_NAMESPACE:
            ns_problems.append((name, namespace or '(глобальный)',
                                ', '.join(sorted(x or '(глобальный)' for x in real_namespaces))))

        for member in sorted(members):
            if member in real_members or member == name:
                continue
            if name + '.' + member in ALLOWED_MEMBERS:
                continue
            member_problems.append((name, member))

    if ns_problems:
        print('НЕВЕРНЫЙ NAMESPACE (плагин не соберётся на сервере):')
        for name, mine_ns, real_ns in ns_problems:
            print('  %-28s заглушка: %-22s игра: %s' % (name, mine_ns, real_ns))
        print()

    if member_problems:
        print('ЧЛЕН ОТСУТСТВУЕТ В ИГРЕ:')
        for name, member in member_problems:
            print('  %s.%s' % (name, member))
        print()

    if unknown:
        print('Типов нет в дампе (вспомогательные для тестов): %d — %s'
              % (len(unknown), ', '.join(unknown)))
        print()

    broken = len(ns_problems) + len(member_problems)
    print('Проверено типов: %d, расхождений: %d' % (len(mine), broken))
    if not broken:
        print('Заглушки соответствуют дампу.')
    return 1 if broken else 0


if __name__ == '__main__':
    sys.exit(main())
