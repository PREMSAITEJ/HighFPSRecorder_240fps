#ifndef HIGHFPS_FRAME_PROCESSOR_H
#define HIGHFPS_FRAME_PROCESSOR_H

#include <cstdint>
#include <camera2ndk.h>
#include <media/NdkImage.h>
#include <atomic>
#include <vector>
#include <queue>
#include <mutex>
#include <condition_variable>
#include <thread>

class FrameBuffer;
class TIFFEncoder;

/**
 * High-speed frame processor for 240 FPS capture
 * - Acquires frames from AImageReader at native priority
 * - Converts YUV420 → 8-bit grayscale (Y-plane extraction)
 * - Corrects Samsung memory alignment strides
 * - Queues frames for parallel TIFF encoding
 */
class FrameProcessor {
public:
    FrameProcessor(int width, int height, const char* output_dir);
    ~FrameProcessor();

    // Lifecycle
    void start_capturing();
    void stop_capturing();
    bool is_capturing() const;

    // Frame handling
    void on_frame_available(AImage* image);
    void process_frame(AImage* image);

    // Statistics
    uint64_t get_frame_count() const;
    uint64_t get_dropped_frames() const;
    uint64_t get_errors() const;
    double get_avg_processing_time_ms() const;

private:
    // Frame processing pipeline
    void convert_yuv420_to_grayscale(const uint8_t* src_y, int stride_y,
                                     uint8_t* dst_gray, int width, int height);
    void correct_stride(const uint8_t* src, int src_stride,
                       uint8_t* dst, int dst_width, int dst_height);

    // Worker threads
    void encoding_worker();

    // Member variables
    int width_;
    int height_;
    std::string output_dir_;
    std::atomic<bool> is_capturing_;
    std::atomic<uint64_t> frame_count_;
    std::atomic<uint64_t> dropped_frames_;
    std::atomic<uint64_t> error_count_;

    // Threading
    std::queue<std::pair<uint8_t*, uint64_t>> frame_queue_;
    std::mutex queue_mutex_;
    std::condition_variable queue_cv_;
    std::vector<std::thread> encoder_threads_;
    static constexpr int NUM_ENCODER_THREADS = 4;
    static constexpr int MAX_QUEUE_SIZE = 60;

    // Timing
    std::vector<double> processing_times_;
    std::mutex timing_mutex_;

    // Buffers
    std::unique_ptr<FrameBuffer> frame_buffer_;
    std::unique_ptr<TIFFEncoder> tiff_encoder_;
};

#endif // HIGHFPS_FRAME_PROCESSOR_H
