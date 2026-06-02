#!/usr/bin/env python3
"""
Парсит JSON-вывод команды `yard releases list` и возвращает список версий
платформы 1С, которые ещё не были обработаны и не ниже нижней границы.

Формат входного JSON (из yard):
  [
    {
      "Имя": "Технологическая платформа 8.3",
      "Идентификатор": "Platform83",
      "Версии": [
        { "Версия": "8.3.21.1624", "Дата": "...", "ПолныйДистрибутив": true, ... },
        ...
      ]
    }
  ]

Использование:
  yard releases --user "$LOGIN" --pwd "$PWD" list \
    --app-filter "Технологическая платформа 8\.[35]$" \
    --output-file /tmp/yard-versions.json

  python3 scripts/get-new-versions.py \
    --yard-json /tmp/yard-versions.json \
    --processed schemas/processed-versions.json \
    --min-version 8.3.14 \
    --limit 15

Выводит: JSON-массив версий для обработки, отсортированных от старых к новым.
"""

import argparse
import json
import sys
from pathlib import Path


def version_tuple(v: str) -> tuple:
    """'8.3.21.1624' → (8, 3, 21, 1624)"""
    try:
        return tuple(int(x) for x in v.split("."))
    except ValueError:
        return (0,)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--yard-json", required=True, help="JSON-файл из yard releases list")
    parser.add_argument("--processed", required=True, help="Путь к processed-versions.json")
    parser.add_argument(
        "--min-version",
        default="8.3.14",
        help="Нижняя граница версии (минимум 8.3.14 — там появился автономный сервер ibsrv)",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=0,
        help="Максимум версий за прогон (0 — без ограничения)",
    )
    args = parser.parse_args()

    # Загружаем уже обработанные версии
    processed_path = Path(args.processed)
    processed: set[str] = set()
    if processed_path.exists():
        data = json.loads(processed_path.read_text(encoding="utf-8"))
        processed = {k for k in data if not k.startswith("_")}

    # Нижняя граница: сравниваем первые 3 компонента (8.3.14.x >= 8.3.14)
    min_tup = version_tuple(args.min_version)[:3]

    # Парсим JSON из yard
    yard_data = json.loads(Path(args.yard_json).read_text(encoding="utf-8"))

    all_versions: list[str] = []
    for app in yard_data:
        for ver_obj in app.get("Версии", []):
            ver = ver_obj.get("Версия", "")
            if not ver:
                continue
            # Только релизные (не бета) версии с полным дистрибутивом
            if ver_obj.get("Бета", False):
                continue
            all_versions.append(ver)

    # Фильтруем: >= min_version и ещё не обработаны
    new_versions = [
        v for v in all_versions
        if version_tuple(v)[:3] >= min_tup and v not in processed
    ]

    # Сортируем от старых к новым
    new_versions.sort(key=version_tuple)

    # Ограничение размера батча (для укладывания в лимит времени job)
    if args.limit > 0:
        new_versions = new_versions[: args.limit]

    print(json.dumps(new_versions, ensure_ascii=False))


if __name__ == "__main__":
    main()
