package com.example.shortlinks;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Scanner;
import java.util.UUID;

final class Console {
    private final Scanner input;
    private final LinkService service;
    private final int defaultLimit;
    private final PrintStream out;
    private final LinkService.Browser browser;
    private UUID user;

    Console(Scanner input, LinkService service, int defaultLimit, PrintStream out, LinkService.Browser browser) {
        this.out = out;
        this.browser = browser;
        this.input = input;
        this.service = service;
        this.defaultLimit = defaultLimit;
    }

    void run() throws IOException {
        out.println("""
                Вставьте полный URL, чтобы сократить его. Для перехода вставьте полученную короткую ссылку.
                При создании ссылки вы получите UUID для входа. Уже есть UUID? Введите login и ваш UUID.
                id — показать текущий UUID, help — все команды, exit — завершить программу.""");
        while (true) {
            showNotices();
            out.print("> ");
            if (!input.hasNextLine()) {
                return;
            }
            String line = input.nextLine().strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.equalsIgnoreCase("exit")) {
                return;
            }
            execute(line);
        }
    }

    synchronized void showNotices() throws IOException {
        if (user != null) {
            for (String message : service.takeNotices(user)) {
                out.println(message);
            }
        }
    }

    private synchronized void execute(String line) throws IOException {
        String[] args = line.split("\\s+");
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "help" -> out.println("""
                        create URL [лимит]    сократить ссылку
                        URL                   сократить ссылку с обычным лимитом
                        list                  показать мои ссылки
                        limit ссылка число    изменить общий лимит
                        target ссылка URL     изменить адрес назначения
                        delete ссылка         удалить свою ссылку
                        login UUID            вернуться к своим ссылкам
                        id                    показать свой UUID
                        logout                выйти из профиля и продолжить работу
                        exit                  завершить работу
                        Пример: create https://example.org/articles/java 3
                        Для перехода вставьте короткую ссылку отдельной строкой.""");
                case "create" -> {
                    if (args.length != 2 && args.length != 3) {
                        throw new IllegalArgumentException("Команда: create URL [лимит]");
                    }
                    create(parseAddress(args[1]), args.length == 3 ? positive(args[2]) : defaultLimit);
                }
                case "list" -> {
                    expect(args, 1);
                    var links = service.list(currentUser());
                    if (links.isEmpty()) {
                        out.println("Активных ссылок нет.");
                    }
                    for (ShortLink link : links) {
                        out.printf("%s → %s | переходов %d/%d | действует до %s%n",
                                link.address(), link.target(), link.visits(), link.limit(), link.expiresAt());
                    }
                }
                case "limit" -> {
                    expect(args, 3);
                    service.changeLimit(currentUser(), code(args[1]), positive(args[2]));
                    out.println("Лимит изменён.");
                }
                case "target" -> {
                    expect(args, 3);
                    service.changeTarget(currentUser(), code(args[1]), parseAddress(args[2]));
                    out.println("Адрес изменён.");
                }
                case "delete" -> {
                    expect(args, 2);
                    service.delete(currentUser(), code(args[1]));
                    out.println("Ссылка удалена.");
                }
                case "login" -> {
                    expect(args, 2);
                    user = service.login(args[1]);
                    out.println("Пользователь выбран.");
                }
                case "id" -> {
                    expect(args, 1);
                    out.println("Ваш UUID: " + currentUser());
                }
                case "logout" -> {
                    expect(args, 1);
                    user = null;
                    out.println("Вы вышли. Для возврата используйте login UUID.");
                }
                default -> {
                    expect(args, 1);
                    URI address = parseAddress(line);
                    if (ShortLink.HOST.equalsIgnoreCase(address.getHost())) {
                        service.open(code(line), browser);
                        out.println("Ссылка открыта.");
                    } else if ("https".equalsIgnoreCase(address.getScheme())
                            || "http".equalsIgnoreCase(address.getScheme())) {
                        create(address, defaultLimit);
                    } else {
                        throw new IllegalArgumentException("Введите URL с https:// или http://. Список команд: help.");
                    }
                }
            }
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            out.println(e.getMessage());
        }
    }

    private void create(URI address, int limit) throws IOException {
        UUID owner = user == null ? UUID.randomUUID() : user;
        ShortLink link = service.create(owner, address, limit);
        if (user == null) {
            user = owner;
            out.println("Сохраните ваш UUID для входа: " + user);
        }
        out.println(link.address());
    }

    private UUID currentUser() {
        if (user == null) {
            throw new IllegalArgumentException("Создайте ссылку или введите login UUID.");
        }
        return user;
    }

    private static URI parseAddress(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Некорректный адрес: " + value, e);
        }
    }

    private static String code(String address) {
        String prefix = ShortLink.PREFIX;
        if (!address.regionMatches(true, 0, prefix, 0, prefix.length())
                || !address.substring(prefix.length()).matches("[0-9a-z]{1,13}")) {
            throw new IllegalArgumentException("Скопируйте короткую ссылку целиком из списка (команда list).");
        }
        return address.substring(prefix.length());
    }

    private static void expect(String[] args, int count) {
        if (args.length != count) {
            throw new IllegalArgumentException("Проверьте аргументы команды. Справка: help.");
        }
    }

    private static int positive(String value) {
        try {
            int number = Integer.parseInt(value);
            if (number <= 0) {
                throw new IllegalArgumentException("Требуется целое положительное число.");
            }
            return number;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Требуется целое положительное число.", e);
        }
    }
}
