#include "tiff_encoder.h"
#include <android/log.h>
#include <fstream>
#include <cstring>
#include <sstream>
#include <iomanip>
#include <ctime>
#include <sys/stat.h>

#define LOG_TAG "HighFPS-NDK"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// TIFF tag constants
const uint16_t TIFF_TAG_IMAGE_WIDTH = 0x0100;
const uint16_t TIFF_TAG_IMAGE_LENGTH = 0x0101;
const uint16_t TIFF_TAG_BITS_PER_SAMPLE = 0x0102;
const uint16_t TIFF_TAG_COMPRESSION = 0x0103;
const uint16_t TIFF_TAG_PHOTOMETRIC_INTERP = 0x0106;
const uint16_t TIFF_TAG_STRIP_OFFSETS = 0x0111;
const uint16_t TIFF_TAG_SAMPLES_PER_PIXEL = 0x0115;
const uint16_t TIFF_TAG_ROWS_PER_STRIP = 0x0116;
const uint16_t TIFF_TAG_STRIP_BYTE_COUNT = 0x0117;
const uint16_t TIFF_TAG_X_RESOLUTION = 0x011A;

TIFFEncoder::TIFFEncoder(int width, int height, const char* output_dir)
    : width_(width), height_(height), output_dir_(output_dir),
      encoded_count_(0), y_plane_size_(width * height),
      pixel_data_size_(width * height) {
    LOGD("TIFFEncoder initialized: %dx%d grayscale", width, height);

    // Ensure output directory exists
    mkdir(output_dir, 0755);
}

TIFFEncoder::~TIFFEncoder() {
    LOGD("TIFFEncoder destroyed. Encoded %llu frames", encoded_count_);
}

bool TIFFEncoder::encode_grayscale_tiff(const uint8_t* gray_data,
                                        uint64_t frame_number) {
    if (!gray_data) {
        LOGE("Null gray data for frame %llu", frame_number);
        return false;
    }

    std::string filename = get_frame_filename(frame_number);

    try {
        std::ofstream file(filename, std::ios::binary);
        if (!file.is_open()) {
            LOGE("Failed to open %s", filename.c_str());
            return false;
        }

        // Calculate sizes
        const uint32_t header_size = 8;
        const uint32_t ifd_entry_count = 9;
        const uint32_t ifd_size = 2 + (ifd_entry_count * 12) + 4;  // count + entries + next IFD offset
        const uint32_t strip_offset = header_size + ifd_size;
        const uint32_t strip_byte_count = pixel_data_size_;

        // Build TIFF structure in memory
        std::vector<uint8_t> tiff_data;

        // Header (8 bytes)
        write_tiff_header(tiff_data);

        // IFD (Image File Directory)
        write_ifd(tiff_data, strip_offset, strip_byte_count);

        // Write pixel data
        file.write(reinterpret_cast<char*>(tiff_data.data()), tiff_data.size());
        file.write(reinterpret_cast<const char*>(gray_data), pixel_data_size_);
        file.close();

        encoded_count_++;
        if (frame_number % 60 == 0) {
            LOGD("Encoded frame %llu → %s", frame_number, filename.c_str());
        }
        return true;

    } catch (const std::exception& e) {
        LOGE("Failed to encode frame %llu: %s", frame_number, e.what());
        return false;
    }
}

void TIFFEncoder::write_tiff_header(std::vector<uint8_t>& buffer) {
    TIFFHeader header;
    header.byte_order[0] = 'I';   // Little-endian
    header.byte_order[1] = 'I';
    header.magic = 42;            // TIFF magic number
    header.ifd_offset = 8;        // IFD starts at offset 8

    const uint8_t* ptr = reinterpret_cast<uint8_t*>(&header);
    buffer.insert(buffer.end(), ptr, ptr + sizeof(TIFFHeader));
}

void TIFFEncoder::write_ifd(std::vector<uint8_t>& buffer, uint32_t strip_offset,
                            uint32_t strip_byte_count) {
    // IFD entry count
    uint16_t entry_count = 9;
    buffer.push_back(entry_count & 0xFF);
    buffer.push_back((entry_count >> 8) & 0xFF);

    auto add_tag = [&](uint16_t tag, uint16_t type, uint32_t count,
                       uint32_t value) {
        TIFFTag t;
        t.tag = tag;
        t.type = type;
        t.count = count;
        t.value_or_offset = value;
        const uint8_t* ptr = reinterpret_cast<uint8_t*>(&t);
        buffer.insert(buffer.end(), ptr, ptr + sizeof(TIFFTag));
    };

    // Tags: tag_id, type (3=SHORT, 4=LONG, 5=RATIONAL), count, value/offset
    add_tag(TIFF_TAG_IMAGE_WIDTH, 4, 1, width_);                    // ImageWidth
    add_tag(TIFF_TAG_IMAGE_LENGTH, 4, 1, height_);                  // ImageLength
    add_tag(TIFF_TAG_BITS_PER_SAMPLE, 3, 1, 8);                     // BitsPerSample (8)
    add_tag(TIFF_TAG_COMPRESSION, 3, 1, 1);                         // Compression (1=no compression)
    add_tag(TIFF_TAG_PHOTOMETRIC_INTERP, 3, 1, 1);                  // PhotometricInterpretation (1=BlackIsZero for grayscale)
    add_tag(TIFF_TAG_STRIP_OFFSETS, 4, 1, strip_offset);            // StripOffsets
    add_tag(TIFF_TAG_SAMPLES_PER_PIXEL, 3, 1, 1);                   // SamplesPerPixel (1 for grayscale)
    add_tag(TIFF_TAG_ROWS_PER_STRIP, 4, 1, height_);                // RowsPerStrip
    add_tag(TIFF_TAG_STRIP_BYTE_COUNT, 4, 1, strip_byte_count);     // StripByteCounts

    // Next IFD offset (0 = no more IFDs)
    uint32_t next_ifd = 0;
    const uint8_t* ptr = reinterpret_cast<uint8_t*>(&next_ifd);
    buffer.insert(buffer.end(), ptr, ptr + 4);
}

std::string TIFFEncoder::get_frame_filename(uint64_t frame_number) const {
    std::ostringstream oss;
    oss << output_dir_ << "/frame_"
        << std::setfill('0') << std::setw(6) << frame_number
        << "_" << std::time(nullptr) << ".tiff";
    return oss.str();
}

uint64_t TIFFEncoder::get_encoded_count() const {
    return encoded_count_;
}
