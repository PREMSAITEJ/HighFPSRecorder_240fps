#include "frame_processor.h"
#include "frame_buffer.h"
#include "tiff_encoder.h"
#include "stride_corrector.h"
#include <android/log.h>
#include <chrono>
#include <cstring>
#include <sstream>

#define LOG_TAG "HighFPS-NDK"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

FrameProcessor::FrameProcessor(int width, int height, const char* output_dir)
    : width_(width), height_(height), output_dir_(output_dir),
      is_capturing_(false), frame_count_(0), dropped_frames_(0), error_count_(0) {
    LOGD("FrameProcessor initialized: %dx%d → %s", width, height, output_dir);
    frame_buffer_ = std::make_unique<FrameBuffer>(width * height + 1024);
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

    LOGD("Starting capture with %d encoder threads", NUM_ENCODER_THREADS);

    // Launch encoder threads
    for (int i = 0; i < NUM_ENCODER_THREADS; ++i) {
        encoder_threads_.emplace_back([this]() { this->encoding_worker(); });
    }
}

void FrameProcessor::stop_capturing() {
    if (!is_capturing_.exchange(false)) {
        return;
    }

    LOGD("Stopping capture...");
    queue_cv_.notify_all();

    // Wait for encoder threads
    for (auto& t : encoder_threads_) {
        if (t.joinable()) {
            t.join();
        }
    }
    encoder_threads_.clear();

    LOGD("Capture stopped. Final stats: %llu frames, %llu dropped, %llu errors",
         frame_count_.load(), dropped_frames_.load(), error_count_.load());
}

bool FrameProcessor::is_capturing() const {
    return is_capturing_.load();
}

void FrameProcessor::on_frame_available(AImage* image) {
    if (!is_capturing_.load()) {
        AImage_delete(image);
        return;
    }

    // Acquire grayscale buffer from frame buffer
    uint8_t* gray_buffer = frame_buffer_->acquire_buffer();
    if (!gray_buffer) {
        LOGW("Failed to acquire frame buffer");
        AImage_delete(image);
        dropped_frames_++;
        return;
    }

    // Process frame
    auto start = std::chrono::high_resolution_clock::now();
    process_frame_internal(image, gray_buffer);
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

    // Queue for encoding
    uint64_t frame_num = frame_count_++;
    {
        std::lock_guard<std::mutex> lock(queue_mutex_);
        if (frame_queue_.size() >= MAX_QUEUE_SIZE) {
            LOGW("Frame queue full, dropping frame %llu", frame_num);
            frame_buffer_->release_buffer(gray_buffer);
            dropped_frames_++;
        } else {
            frame_queue_.push({gray_buffer, frame_num});
            queue_cv_.notify_one();
        }
    }

    AImage_delete(image);
}

void FrameProcessor::process_frame(AImage* image) {
    uint8_t* gray_buffer = frame_buffer_->acquire_buffer();
    if (!gray_buffer) {
        LOGW("Failed to acquire frame buffer");
        dropped_frames_++;
        return;
    }

    // Extract Y-plane (luminance)
    uint8_t* y_plane = nullptr;
    int y_size = 0;
    int y_stride = 0;

    int status = AImage_getPlaneData(image, 0, &y_plane, &y_size);
    if (status != AMEDIA_OK) {
        LOGE("Failed to get Y-plane: %d", status);
        frame_buffer_->release_buffer(gray_buffer);
        error_count_++;
        return;
    }

    AImage_getPlaneRowPitch(image, 0, &y_stride);

    // Correct stride for Samsung memory alignment
    StrideCorrector corrector;
    corrector.correct_stride(y_plane, y_stride, gray_buffer, width_, height_);

    // Queue for encoding
    uint64_t frame_num = frame_count_++;
    {
        std::lock_guard<std::mutex> lock(queue_mutex_);
        if (frame_queue_.size() >= MAX_QUEUE_SIZE) {
            LOGW("Frame queue full at frame %llu", frame_num);
            frame_buffer_->release_buffer(gray_buffer);
            dropped_frames_++;
        } else {
            frame_queue_.push({gray_buffer, frame_num});
            queue_cv_.notify_one();
        }
    }
}

void FrameProcessor::encoding_worker() {
    LOGD("Encoder worker thread started");

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

        // Encode to TIFF
        bool success = tiff_encoder_->encode_grayscale_tiff(gray_buffer, frame_num);
        if (!success) {
            LOGW("Failed to encode frame %llu", frame_num);
            error_count_++;
        } else {
            if (frame_num % 60 == 0) {
                LOGD("Encoded frame %llu", frame_num);
            }
        }

        frame_buffer_->release_buffer(gray_buffer);
    }

    LOGD("Encoder worker thread stopping");
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

void FrameProcessor::convert_yuv420_to_grayscale(const uint8_t* src_y,
                                                 int stride_y,
                                                 uint8_t* dst_gray,
                                                 int width, int height) {
    for (int y = 0; y < height; ++y) {
        std::memcpy(&dst_gray[y * width],
                    &src_y[y * stride_y],
                    width);
    }
}

void FrameProcessor::correct_stride(const uint8_t* src, int src_stride,
                                    uint8_t* dst, int dst_width, int dst_height) {
    for (int y = 0; y < dst_height; ++y) {
        std::memcpy(&dst[y * dst_width],
                    &src[y * src_stride],
                    dst_width);
    }
}
