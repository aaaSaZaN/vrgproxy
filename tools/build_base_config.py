#!/usr/bin/env python3
"""
Собирает базовый конфиг приложения из шаблона Remnawave.

Приложение не парсит подписку: серверы тянет само ядро через proxy-provider.
Поэтому из шаблона выкидываются proxies и всё, что относится к десктопному
TUN-режиму, а группа переключается на провайдер.

Запуск:
    python3 tools/build_base_config.py <шаблон.yaml> app/src/main/assets/base_config.yaml
"""
import sys
import yaml

SUBSCRIPTION_PLACEHOLDER = "__SUBSCRIPTION_URL__"
GROUP = "VrgProxy"
PROVIDER = "subscription"


def main(src: str, dst: str) -> None:
    root = yaml.safe_load(open(src, encoding="utf-8"))

    # Серверы приходят из подписки через провайдер, статического списка нет.
    root.pop("proxies", None)
    root["proxy-providers"] = {
        PROVIDER: {
            "type": "http",
            "url": SUBSCRIPTION_PLACEHOLDER,
            "path": "./providers/subscription.yaml",
            "interval": 86400,
            "health-check": {
                "enable": True,
                "url": "https://cp.cloudflare.com/generate_204",
                "interval": 300,
            },
        }
    }
    # Одна группа и только она. Отдельной группы для финального правила не надо:
    # приложение само переписывает MATCH на нужную цель. Лишняя группа была бы
    # видна в списке серверов, и через неё можно случайно завернуть в VPN всё.
    root["proxy-groups"] = [
        {"name": GROUP, "type": "select", "use": [PROVIDER]},
    ]

    # Вход: телефон слушает локальную сеть. Порт и пароль подставляет приложение.
    root["allow-lan"] = True
    root["bind-address"] = "*"
    root["lan-allowed-ips"] = ["0.0.0.0/0", "::/0"]
    root["skip-auth-prefixes"] = ["127.0.0.1/32"]
    for key in ("redir-port", "tproxy-port", "port", "socks-port", "tun",
                "external-ui", "authentication",
                # Ключ удалён из mihomo: ядро пишет ошибку и просит задавать
                # client-fingerprint на самом прокси.
                "global-client-fingerprint"):
        root.pop(key, None)

    root["mode"] = "rule"
    root["log-level"] = "info"
    # По умолчанию ядро шлёт "clash.meta/<версия сборки>". Панели раздают конфиг
    # по User-Agent, поэтому фиксируем его, чтобы он не поехал при обновлении ядра.
    root["global-ua"] = "clash.meta/1.19.0"
    root["ipv6"] = False
    root["geo-auto-update"] = False
    root["unified-delay"] = True
    root["tcp-concurrent"] = True

    # Соединения приходят с чужого устройства — владельца процесса не определить.
    root["enable-process"] = False
    root["find-process-mode"] = "off"
    root["profile"] = {"store-selected": False, "store-fake-ip": False}

    if isinstance(root.get("dns"), dict):
        # fake-ip ломает обычный HTTP/SOCKS-вход: клиенту некому отдавать
        # поддельные адреса.
        root["dns"]["enhanced-mode"] = "redir-host"
        root["dns"]["ipv6"] = False

    rules = [str(r) for r in (root.get("rules") or [])]
    kept = []
    for rule in rules:
        parts = [p.strip() for p in rule.split(",")]
        head = parts[0].upper()
        if head.startswith(("PROCESS-NAME", "PROCESS-PATH")):
            continue  # на телефоне не сработают никогда
        if head == "MATCH":
            continue  # финальное правило дописывает приложение
        if len(parts) >= 3 and parts[2] not in ("DIRECT", "REJECT", "REJECT-DROP"):
            parts[2] = GROUP
        kept.append(",".join(parts))
    root["rules"] = kept

    with open(dst, "w", encoding="utf-8") as out:
        out.write("# Сгенерировано tools/build_base_config.py — вручную не править.\n")
        yaml.safe_dump(root, out, allow_unicode=True, default_flow_style=False,
                       sort_keys=False, width=4096)

    print(f"правил: {len(kept)}, записано в {dst}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
