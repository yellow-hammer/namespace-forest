# Namespace Forest

XSD платформы **1С:Предприятие 8** (выгрузка/загрузка конфигурации в XML).

Структура: **`schemas/<версия>/`** — например, **`schemas/2.10/`** (полный набор `*.xsd` в каждой). Здесь **`<версия>`** — это **версия формата выгрузки конфигуратора** (атрибут `version` корневого `MetaDataObject`, он же максимальное значение `FormatVersion` в `…-xcf-enums.xsd`), а **не** номер релиза платформы: несколько релизов платформы могут давать один и тот же формат. Цифры **8.1 / 8.2 / 8.3** в именах отдельных файлов — это namespace внутри схем, а не уровни каталогов.

## Использование в экосистеме

- `md-sparrow` использует эти схемы для JAXB-моделей и валидации XML;
- `vscode-1c-platform-tools` работает с метаданными через `md-sparrow`, поэтому фактическая поддержка типов и версий схем зависит от связки `md-sparrow` + `namespace-forest`.

## Автоматическое обновление схем

Workflow [`.github/workflows/update-schemas.yml`](.github/workflows/update-schemas.yml) — еженедельно и по ручному запуску.

Конвейер для каждой версии платформы:

1. `yard` получает список релизов с [releases.1c.ru](https://releases.1c.ru), отбирает версии не ниже `min_version` и отсутствующие в [`schemas/processed-versions.json`](schemas/processed-versions.json);
2. `yard` скачивает дистрибутив, ставится платформа (компоненты `server` + `client_full`);
3. `ibcmd` создаёт файловую ИБ и загружает конфигурацию из [`src/`](src/) с HTTP-сервисом `ВыгрузкаСхем`;
4. `ibsrv` публикует сервис; `GET /hs/xsd/dump` возвращает XSD.

Номер каталога — максимум `FormatVersion` из выгруженного `…-xcf-enums.xsd`.

**Секреты** (Settings → Secrets → Actions): `ONEC_LOGIN`, `ONEC_PASSWORD` — учётные данные [releases.1c.ru](https://releases.1c.ru).

Каталог [`src/`](src/) — конфигурация для извлечения схем, не часть набора `schemas/`.

## Лицензия

`*.xsd` — авторские права **ООО «1С-Софт»**, не MIT. См. [LICENSE](LICENSE).

## Для разработчиков и ИИ

Правила Cursor: **`.cursor/rules/*.mdc`** (контекст XSD, связь с md-sparrow).

## Автор

Ivan Karlo (<i.karlo@outlook.com>)

При желании, отблагодарить автора можно по ссылке:

- [Boosty](https://boosty.to/1carlo/donate)
- [Чаевые](https://pay.cloudtips.ru/p/d752cb43)
