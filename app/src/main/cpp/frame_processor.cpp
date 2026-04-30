#include "frame_processor.h"
#include "frame_buffer.h"
#include "tiff_encoder.h"
#include <android/log.h>
#include <chrono>
#include <cstring>
#include <sstream>

#define LOG_TAG "HighFPS-RawFrames"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

FrameProcessor::FrameProcessor(int width, int height, const char* output_dir)
    : width_(width), height_(height), output_dir_(output_dir),
      is_capturing_(false), frame_count_(0), dropped_frames_(0), error_count_(0) {
    LOGD("FrameProcessor init: %dx%d Y-plane → %s", width, height, output_dir);
    frame_buffer_ = std::make_unique<FrameBuffer>(width * height);  // Y-plane only
    tiff_encoder_ = std::make_unique<TIFFEncoder>(width, height, output_dir);
}

FrameProcessor::~FrameProcessor() {
    stop_capturing();
    LOGD("FrameProcessor destroyed. Total frames: %llu, Dropped: %llu",
         frame_count_.load(), dropped_frames_.load());
}

void FrameProcessor::start_capturing() {
    if (is_capturing_.exchange(true)) {
        LOGW("Already capturing");
        return;
    }

    LOGD("Starting RAW frame capture with %d encoder threads", NUM_ENCODER_THREADS);

    // Launch encoder threads
    for (int i = 0; i < NUM_ENCODER_THREADS; ++i) {
        encoder_threads_.emplace_back([this]() { this->encoding_worker(); });
    }
}

void FrameProcessor::stop_capturing() {
    if (!is_capturing_.exchange(false)) {
        return;
    }

    LOGD("Stopping RAW frame capture...");
    queue_cv_.notify_all();

    // Wait for encoder threads
    for (auto& t : encoder_threads_) {
        if (t.joinable()) {
            t.join();
        }
    }
    encoder_threads_.clear();

    LOGD("Capture stopped. Final: %llu frames, %llu dropped, %llu errors",
         frame_count_.load(), dropped_frames_.load(), error_count_.load());
}

bool FrameProcessor::is_capturing() const {
    return is_capturing_.load();
}

void FrameProcessor::process_raw_yplane(const uint8_t* y_data, int pixel_stride, int row_pitch) {
    if (!is_capturing_.load()) {
        return;
    }

    auto start = std::chrono::high_resolution_clock::now();

    // Acquire buffer
    uint8_t* gray_buffer = frame_buffer_->acquire_buffer();
    if (!gray_buffer) {
        LOGW("Failed to acquire frame buffer");
        dropped_frames_++;
        return;
    }

    // Correct stride from camera sensor
    correct_stride(y_data, row_pitch, gray_buffer, width_, height_);

    auto end = std::chrono::high_resolution_clock::now();
    double ms = std::chrono::duration<double, std::milli>(end - start).count();

    // Record timing
    {
        std::lock_guard<std::mutex> lock(timing_mutex_);
        processing_times_.push_back(ms);
        if (processing_times_.size() > 1000) {
            processing_times_.erase(processing_times_.begin());
        }
    }

    // Queue for TIFF encoding
    uint64_t frame_num = frame_count_++;
    {
        std::lock_guard<std::mutex> lock(queue_mutex_);
        if (frame_queue_.size() >= MAX_QUEUE_SIZE) {
            LOGW("Frame queue full, dropping frame %llu (queue: %zu)", 
                 frame_num, frame_queue_.size());
            frame_buffer_->release_buffer(gray_buffer);
            dropped_frames_++;
        } else {
            frame_queue_.push({gray_buffer, frame_num});
            queue_cv_.notify_one();
        }
    }
}

void FrameProcessor::encoding_worker() {
    LOGD("TIFF encoder worker thread started");

    while (true) {
        std::unique_lock<std::mutex> lock(queue_mutex_);
        queue_cv_.wait(lock, [this]() {
            return !frame_queue_.empty() || !is_capturing_.load();
        });

        if (frame_queue_.empty()) {
            if (!is_capturing_.load()) {
                break;
            }
            continue;
        }

        auto [gray_buffer, frame_num] = frame_queue_.front();
        frame_queue_.pop();
        lock.unlock();

        // Encode Y-plane to 8-bit grayscale TIFF
        bool success = tiff_encoder_->encode_grayscale_tiff(gray_buffer, frame_num);
        if (!success) {
            LOGW("Failed to encode frame %llu to TIFF", frame_num);
            error_count_++;
        } else {
            if (frame_num % 60 == 0) {  // Log every 60 frames (~250ms at 240fps)
                LOGD("✓ Frame %llu → TIFF", frame_num);
            }
        }

        frame_buffer_->release_buffer(gray_buffer);
    }

    LOGD("TIFF encoder worker thread stopping");
}

void FrameProcessor::correct_stride(const uint8_t* src, int src_stride,
                                    uint8_t* dst, int dst_width, int dst_height) {
    // Samsung sensors may have alignment padding in row pitch
    // Example: 1920-wide but 2048-byte stride
    // This copies only the valid width, skipping padding bytes
    for (int y = 0; y < dst_height; ++y) {
        std::memcpy(&dst[y * dst_width],
                    &src[y * src_stride],
                    dst_width);
    }
}

uint64_t FrameProcessor::get_frame_count() const {
    return frame_count_.load();
}

uint64_t FrameProcessor::get_dropped_frames() const {
    return dropped_frames_.load();
}

uint64_t FrameProcessor::get_errors() const {
    return error_count_.load();
}

double FrameProcessor::get_avg_processing_time_ms() const {
    std::lock_guard<std::mutex> lock(timing_mutex_);
    if (processing_times_.empty()) {
        return 0.0;
    }
    double sum = 0.0;
    for (double t : processing_times_) {
        sum += t;
    }
    return sum / processing_times_.size();
}
