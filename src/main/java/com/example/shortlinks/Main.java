package com.example.shortlinks;

import java.awt.Desktop;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) {
        Properties config = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of("shortlinks.properties"))) {
            config.load(reader);
            int seconds = setting(config, "ttl.seconds");
            int limit = setting(config, "click.limit");
            Path data = Files.createDirectories(Path.of("data"));
            try (FileChannel channel = FileChannel.open(data.resolve("lock"), StandardOpenOption.WRITE, StandardOpenOption.CREATE);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) {
                    throw new IOException("Сервис уже запущен из этой папки.");
                }
                LinkService service = new LinkService(new LinkStore(data.resolve("links.tsv")),
                        Clock.systemUTC(), Duration.ofSeconds(seconds));
                run(service, limit);
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Ошибка: " + e.getMessage());
            System.exit(1);
        }
    }

    private static int setting(Properties config, String name) {
        try {
            int value = Integer.parseInt(config.getProperty(name));
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Параметр " + name + " в shortlinks.properties должен быть целым числом.", e);
        }
        throw new IllegalArgumentException("Параметр " + name + " в shortlinks.properties должен быть больше нуля.");
    }

    private static void run(LinkService service, int limit) throws IOException {
        service.removeExpired();
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        try (Scanner input = new Scanner(System.in)) {
            Console console = new Console(input, service, limit, System.out, Main::browse);
            timer.scheduleWithFixedDelay(() -> {
                try {
                    service.removeExpired();
                    console.showNotices();
                } catch (IOException e) {
                    System.err.println("Не удалось сохранить данные: " + e.getMessage());
                }
            }, 1, 1, TimeUnit.SECONDS);
            console.run();
        } finally {
            timer.shutdown();
            try {
                if (!timer.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IOException("Не удалось завершить фоновую очистку.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Остановка сервиса прервана.", e);
            }
        }
    }

    private static void browse(URI target) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            throw new UnsupportedOperationException("Открытие браузера недоступно в этом окружении.");
        }
        try {
            Desktop.getDesktop().browse(target);
        } catch (IOException e) {
            throw new UnsupportedOperationException("Не удалось открыть браузер: " + e.getMessage(), e);
        }
    }
}
