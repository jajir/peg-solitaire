#define _DARWIN_C_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/param.h>
#include <sys/statvfs.h>
#include <time.h>
#include <unistd.h>

enum {
    DEFAULT_SIZE_MIB = 1024,
    DEFAULT_TRIALS = 5,
    DEFAULT_BLOCK_KIB = 1024,
    MIN_SIZE_MIB = 64,
    MAX_TRIALS = 100,
    MIN_BLOCK_KIB = 4,
    MAX_BLOCK_KIB = 16384
};

struct statistics {
    int count;
    double mean;
    double squared_deviation;
    double minimum;
    double maximum;
};

static char temporary_path[MAXPATHLEN];

static void remove_temporary_file(void) {
    if (temporary_path[0] != '\0') {
        (void)unlink(temporary_path);
        temporary_path[0] = '\0';
    }
}

static void handle_signal(int signal_number) {
    remove_temporary_file();
    _exit(128 + signal_number);
}

static void fail_with_errno(const char *operation) {
    fprintf(stderr, "%s: %s\n", operation, strerror(errno));
    exit(EXIT_FAILURE);
}

static uint64_t parse_positive(const char *text, const char *name,
        uint64_t minimum, uint64_t maximum) {
    char *end = NULL;
    errno = 0;
    const unsigned long long value = strtoull(text, &end, 10);
    if (errno != 0 || end == text || *end != '\0' || value < minimum
            || value > maximum) {
        fprintf(stderr, "%s must be between %llu and %llu\n", name,
                (unsigned long long)minimum, (unsigned long long)maximum);
        exit(EXIT_FAILURE);
    }
    return (uint64_t)value;
}

static double monotonic_seconds(void) {
    struct timespec time;
    if (clock_gettime(CLOCK_MONOTONIC_RAW, &time) != 0) {
        fail_with_errno("clock_gettime");
    }
    return (double)time.tv_sec + (double)time.tv_nsec / 1000000000.0;
}

static void disable_cache(int descriptor) {
    if (fcntl(descriptor, F_NOCACHE, 1) == -1) {
        fail_with_errno("fcntl(F_NOCACHE)");
    }
}

static void fill_incompressible(void *buffer, size_t length) {
    uint64_t state = UINT64_C(0x4d595df4d0f33173);
    uint64_t *words = buffer;
    const size_t word_count = length / sizeof(*words);
    for (size_t index = 0; index < word_count; index++) {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        words[index] = state;
    }
}

static void write_all(int descriptor, const void *buffer, size_t block_size,
        uint64_t total_size) {
    uint64_t written = 0;
    while (written < total_size) {
        const uint64_t remaining = total_size - written;
        const size_t requested = remaining < block_size
                ? (size_t)remaining : block_size;
        const ssize_t result = write(descriptor, buffer, requested);
        if (result < 0) {
            if (errno == EINTR) {
                continue;
            }
            fail_with_errno("write");
        }
        if (result == 0) {
            fprintf(stderr, "write returned zero before the file was complete\n");
            exit(EXIT_FAILURE);
        }
        written += (uint64_t)result;
    }
}

static void read_all(int descriptor, void *buffer, size_t block_size,
        uint64_t total_size) {
    uint64_t read_bytes = 0;
    while (read_bytes < total_size) {
        const uint64_t remaining = total_size - read_bytes;
        const size_t requested = remaining < block_size
                ? (size_t)remaining : block_size;
        const ssize_t result = read(descriptor, buffer, requested);
        if (result < 0) {
            if (errno == EINTR) {
                continue;
            }
            fail_with_errno("read");
        }
        if (result == 0) {
            fprintf(stderr, "unexpected end of benchmark file\n");
            exit(EXIT_FAILURE);
        }
        read_bytes += (uint64_t)result;
    }
}

static double run_write_trial(int descriptor, const void *buffer,
        size_t block_size, uint64_t total_size) {
    if (ftruncate(descriptor, 0) != 0) {
        fail_with_errno("ftruncate");
    }
    if (lseek(descriptor, 0, SEEK_SET) == (off_t)-1) {
        fail_with_errno("lseek");
    }
    const double start = monotonic_seconds();
    write_all(descriptor, buffer, block_size, total_size);
    if (fsync(descriptor) != 0) {
        fail_with_errno("fsync");
    }
    return monotonic_seconds() - start;
}

static double run_read_trial(void *buffer, size_t block_size,
        uint64_t total_size) {
    const int descriptor = open(temporary_path, O_RDONLY | O_CLOEXEC);
    if (descriptor == -1) {
        fail_with_errno("open benchmark file for reading");
    }
    disable_cache(descriptor);
    if (fcntl(descriptor, F_RDAHEAD, 1) == -1) {
        fail_with_errno("fcntl(F_RDAHEAD)");
    }
    const double start = monotonic_seconds();
    read_all(descriptor, buffer, block_size, total_size);
    const double elapsed = monotonic_seconds() - start;
    if (close(descriptor) != 0) {
        fail_with_errno("close benchmark reader");
    }
    return elapsed;
}

static void add_sample(struct statistics *statistics, double value) {
    if (statistics->count == 0) {
        statistics->minimum = value;
        statistics->maximum = value;
    } else {
        if (value < statistics->minimum) {
            statistics->minimum = value;
        }
        if (value > statistics->maximum) {
            statistics->maximum = value;
        }
    }
    statistics->count++;
    const double difference = value - statistics->mean;
    statistics->mean += difference / statistics->count;
    statistics->squared_deviation += difference
            * (value - statistics->mean);
}

static double standard_deviation(const struct statistics *statistics) {
    return statistics->count > 1
            ? sqrt(statistics->squared_deviation / (statistics->count - 1))
            : 0.0;
}

static void show_summary(const char *name,
        const struct statistics *statistics) {
    printf("%-18s avg=%8.2f MiB/s  sd=%8.2f  min=%8.2f  max=%8.2f\n",
            name, statistics->mean, standard_deviation(statistics),
            statistics->minimum, statistics->maximum);
}

static void require_free_space(const char *directory, uint64_t total_size) {
    struct statvfs file_system;
    if (statvfs(directory, &file_system) != 0) {
        fail_with_errno("statvfs");
    }
    const uint64_t available = (uint64_t)file_system.f_bavail
            * file_system.f_frsize;
    if (available < total_size + UINT64_C(134217728)) {
        fprintf(stderr, "not enough free space for the benchmark file\n");
        exit(EXIT_FAILURE);
    }
}

int main(int argc, char **argv) {
    if (argc < 2 || argc > 5) {
        fprintf(stderr, "Usage: %s directory [size-MiB [trials [block-KiB]]]\n",
                argv[0]);
        return EXIT_FAILURE;
    }

    const uint64_t size_mib = argc >= 3
            ? parse_positive(argv[2], "size-MiB", MIN_SIZE_MIB, 1048576)
            : DEFAULT_SIZE_MIB;
    const int trials = argc >= 4
            ? (int)parse_positive(argv[3], "trials", 1, MAX_TRIALS)
            : DEFAULT_TRIALS;
    const uint64_t block_kib = argc >= 5
            ? parse_positive(argv[4], "block-KiB", MIN_BLOCK_KIB,
                    MAX_BLOCK_KIB)
            : DEFAULT_BLOCK_KIB;
    if (block_kib % 4 != 0) {
        fprintf(stderr, "block-KiB must be a multiple of 4\n");
        return EXIT_FAILURE;
    }

    const uint64_t total_size = size_mib * UINT64_C(1048576);
    const size_t block_size = (size_t)(block_kib * UINT64_C(1024));
    require_free_space(argv[1], total_size);

    if (snprintf(temporary_path, sizeof(temporary_path),
            "%s/.peg-solitaire-disk-benchmark.XXXXXX", argv[1])
            >= (int)sizeof(temporary_path)) {
        fprintf(stderr, "benchmark directory path is too long\n");
        return EXIT_FAILURE;
    }
    const int writer = mkstemp(temporary_path);
    if (writer == -1) {
        fail_with_errno("mkstemp");
    }
    if (atexit(remove_temporary_file) != 0) {
        fprintf(stderr, "unable to register temporary-file cleanup\n");
        return EXIT_FAILURE;
    }
    (void)signal(SIGINT, handle_signal);
    (void)signal(SIGTERM, handle_signal);
    disable_cache(writer);

    void *buffer = NULL;
    const int allocation_result = posix_memalign(&buffer, 4096, block_size);
    if (allocation_result != 0) {
        errno = allocation_result;
        fail_with_errno("posix_memalign");
    }
    fill_incompressible(buffer, block_size);

    printf("directory=%s size=%llu MiB trials=%d block=%llu KiB cache=disabled\n",
            argv[1], (unsigned long long)size_mib, trials,
            (unsigned long long)block_kib);

    struct statistics writes = {0};
    struct statistics reads = {0};
    for (int trial = 1; trial <= trials; trial++) {
        const double write_seconds = run_write_trial(writer, buffer,
                block_size, total_size);
        const double write_rate = (double)size_mib / write_seconds;
        add_sample(&writes, write_rate);

        const double read_seconds = run_read_trial(buffer, block_size,
                total_size);
        const double read_rate = (double)size_mib / read_seconds;
        add_sample(&reads, read_rate);

        printf("trial=%02d write=%8.2f MiB/s (%6.2fs)  "
                "read=%8.2f MiB/s (%6.2fs)\n", trial, write_rate,
                write_seconds, read_rate, read_seconds);
        fflush(stdout);
    }

    puts("summary:");
    show_summary("durable write", &writes);
    show_summary("sequential read", &reads);
    printf("empirical ceiling  max=%8.2f MiB/s\n",
            writes.maximum > reads.maximum
                    ? writes.maximum : reads.maximum);

    free(buffer);
    if (close(writer) != 0) {
        fail_with_errno("close benchmark writer");
    }
    return EXIT_SUCCESS;
}
