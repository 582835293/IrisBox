/*
 * Pure virtual HWC2 module (hwcomposer.iris.so) for IrisBox Guest containers.
 * Exposes a single 1920x1080@60Hz internal display and forces CLIENT (GLES)
 * composition without opening /dev/dri/card0 or Host framebuffer devices.
 */

#define _GNU_SOURCE
#include <errno.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include <hardware/hardware.h>
#include <hardware/hwcomposer2.h>

#define IRIS_HWC_MAX_LAYERS 256
#define IRIS_HWC_MAX_VDISP 16
#define IRIS_HWC_WIDTH 1920
#define IRIS_HWC_HEIGHT 1080
#define IRIS_HWC_VSYNC_NS 16666667L
#define IRIS_HWC_DPI 240000

struct iris_layer {
    int used;
    hwc2_layer_t id;
    int32_t comp_type;
    int changed;
};

struct iris_hwc2_dev {
    hwc2_device_t base;
    pthread_mutex_t lock;
    pthread_t vsync_thread;
    int vsync_thread_started;
    int stop;
    int vsync_enabled;
    uint64_t next_layer_id;
    uint64_t next_vdisp_id;
    int vdisp_used[IRIS_HWC_MAX_VDISP];
    hwc2_display_t vdisp_ids[IRIS_HWC_MAX_VDISP];
    struct iris_layer layers[IRIS_HWC_MAX_LAYERS];
    HWC2_PFN_HOTPLUG hotplug_cb;
    hwc2_callback_data_t hotplug_data;
    HWC2_PFN_VSYNC vsync_cb;
    hwc2_callback_data_t vsync_data;
    HWC2_PFN_REFRESH refresh_cb;
    hwc2_callback_data_t refresh_data;
};

static struct iris_hwc2_dev* to_dev(hwc2_device_t* dev) {
    return (struct iris_hwc2_dev*)dev;
}

static int valid_display(struct iris_hwc2_dev* d, hwc2_display_t display) {
    if (display == 0) return 1;
    for (int i = 0; i < IRIS_HWC_MAX_VDISP; ++i) {
        if (d->vdisp_used[i] && d->vdisp_ids[i] == display) return 1;
    }
    return 0;
}

static int64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;
}

static void* vsync_loop(void* arg) {
    struct iris_hwc2_dev* d = (struct iris_hwc2_dev*)arg;
    struct timespec req = {0, IRIS_HWC_VSYNC_NS};
    for (;;) {
        nanosleep(&req, NULL);
        pthread_mutex_lock(&d->lock);
        int stop = d->stop;
        int enabled = d->vsync_enabled;
        HWC2_PFN_VSYNC cb = d->vsync_cb;
        hwc2_callback_data_t data = d->vsync_data;
        pthread_mutex_unlock(&d->lock);
        if (stop) break;
        if (enabled && cb) cb(data, 0, now_ns());
    }
    return NULL;
}

static int32_t iris_create_virtual_display(hwc2_device_t* device, uint32_t width, uint32_t height,
                                           int32_t* format, hwc2_display_t* out_display) {
    (void)width;
    (void)height;
    (void)format;
    struct iris_hwc2_dev* d = to_dev(device);
    pthread_mutex_lock(&d->lock);
    for (int i = 0; i < IRIS_HWC_MAX_VDISP; ++i) {
        if (!d->vdisp_used[i]) {
            d->vdisp_used[i] = 1;
            d->vdisp_ids[i] = ++d->next_vdisp_id;
            *out_display = d->vdisp_ids[i];
            pthread_mutex_unlock(&d->lock);
            return HWC2_ERROR_NONE;
        }
    }
    pthread_mutex_unlock(&d->lock);
    return HWC2_ERROR_NO_RESOURCES;
}

static int32_t iris_destroy_virtual_display(hwc2_device_t* device, hwc2_display_t display) {
    struct iris_hwc2_dev* d = to_dev(device);
    pthread_mutex_lock(&d->lock);
    for (int i = 0; i < IRIS_HWC_MAX_VDISP; ++i) {
        if (d->vdisp_used[i] && d->vdisp_ids[i] == display) {
            d->vdisp_used[i] = 0;
            pthread_mutex_unlock(&d->lock);
            return HWC2_ERROR_NONE;
        }
    }
    pthread_mutex_unlock(&d->lock);
    return HWC2_ERROR_BAD_DISPLAY;
}

static void iris_dump(hwc2_device_t* device, uint32_t* out_size, char* out_buffer) {
    (void)device;
    static const char info[] = "IrisBox Virtual HWC2 (1920x1080@60Hz CLIENT)\n";
    uint32_t len = (uint32_t)(sizeof(info) - 1);
    if (!out_buffer) {
        *out_size = len;
        return;
    }
    uint32_t n = (*out_size < len) ? *out_size : len;
    memcpy(out_buffer, info, n);
    *out_size = n;
}

static uint32_t iris_get_max_virtual_display_count(hwc2_device_t* device) {
    (void)device;
    return IRIS_HWC_MAX_VDISP;
}

static int32_t iris_register_callback(hwc2_device_t* device, int32_t descriptor,
                                      hwc2_callback_data_t callback_data,
                                      hwc2_function_pointer_t pointer) {
    struct iris_hwc2_dev* d = to_dev(device);
    HWC2_PFN_HOTPLUG pending_hotplug = NULL;
    hwc2_callback_data_t pending_data = NULL;

    pthread_mutex_lock(&d->lock);
    switch (descriptor) {
        case HWC2_CALLBACK_HOTPLUG:
            d->hotplug_cb = (HWC2_PFN_HOTPLUG)pointer;
            d->hotplug_data = callback_data;
            if (d->hotplug_cb) {
                pending_hotplug = d->hotplug_cb;
                pending_data = d->hotplug_data;
            }
            break;
        case HWC2_CALLBACK_VSYNC:
            d->vsync_cb = (HWC2_PFN_VSYNC)pointer;
            d->vsync_data = callback_data;
            if (d->vsync_cb && !d->vsync_thread_started) {
                if (pthread_create(&d->vsync_thread, NULL, vsync_loop, d) == 0) {
                    d->vsync_thread_started = 1;
                }
            }
            break;
        case HWC2_CALLBACK_REFRESH:
            d->refresh_cb = (HWC2_PFN_REFRESH)pointer;
            d->refresh_data = callback_data;
            break;
        default:
            pthread_mutex_unlock(&d->lock);
            return HWC2_ERROR_BAD_PARAMETER;
    }
    pthread_mutex_unlock(&d->lock);

    if (pending_hotplug) {
        pending_hotplug(pending_data, 0, HWC2_CONNECTION_CONNECTED);
    }
    return HWC2_ERROR_NONE;
}

static int32_t iris_accept_display_changes(hwc2_device_t* device, hwc2_display_t display) {
    struct iris_hwc2_dev* d = to_dev(device);
    pthread_mutex_lock(&d->lock);
    if (!valid_display(d, display)) {
        pthread_mutex_unlock(&d->lock);
        return HWC2_ERROR_BAD_DISPLAY;
    }
    for (int i = 0; i < IRIS_HWC_MAX_LAYERS; ++i) {
        if (d->layers[i].used) {
            d->layers[i].comp_type = HWC2_COMPOSITION_CLIENT;
            d->layers[i].changed = 0;
        }
    }
    pthread_mutex_unlock(&d->lock);
    return HWC2_ERROR_NONE;
}

static int32_t iris_create_layer(hwc2_device_t* device, hwc2_display_t display,
                                 hwc2_layer_t* out_layer) {
    struct iris_hwc2_dev* d = to_dev(device);
    pthread_mutex_lock(&d->lock);
    if (!valid_display(d, display)) {
        pthread_mutex_unlock(&d->lock);
        return HWC2_ERROR_BAD_DISPLAY;
    }
    for (int i = 0; i < IRIS_HWC_MAX_LAYERS; ++i) {
        if (!d->layers[i].used) {
            d->layers[i].used = 1;
            d->layers[i].id = ++d->next_layer_id;
            d->layers[i].comp_type = HWC2_COMPOSITION_CLIENT;
            d->layers[i].changed = 0;
            *out_layer = d->layers[i].id;
            pthread_mutex_unlock(&d->lock);
            return HWC2_ERROR_NONE;
        }
    }
    pthread_mutex_unlock(&d->lock);
    return HWC2_ERROR_NO_RESOURCES;
}

static int32_t iris_destroy_layer(hwc2_device_t* device, hwc2_display_t display,
                                  hwc2_layer_t layer) {
    (void)display;
    struct iris_hwc2_dev* d = to_dev(device);
    pthread_mutex_lock(&d->lock);
    for (int i = 0; i < IRIS_HWC_MAX_LAYERS; ++i) {
        if (d->layers[i].used && d->layers[i].id == layer) {
            d->layers[i].used = 0;
            pthread_mutex_unlock(&d->lock);
            return HWC2_ERROR_NONE;
        }
    }
    pthread_mutex_unlock(&d->lock);
    return HWC2_ERROR_BAD_LAYER;
}

static int32_t iris_get_active_config(hwc2_device_t* device, hwc2_display_t display,
                                      hwc2_config_t* out_config) {
    (void)device;
    (void)display;
    *out_config = 0;
    return HWC2_ERROR_NONE;
}

static int32_t iris_get_changed_composition_types(hwc2_device_t* device, hwc2_display_t display,
                                                  uint32_t* out_num, hwc2_layer_t* out_layers,
                                                  int32_t* out_types) {
    (void)display;
    struct iris_hwc2_dev* d = to_dev(device);
    pthread_mutex_lock(&d->lock);
    uint32_t cap = out_layers ? *out_num : 0;
    uint32_t count = 0;
    for (int i = 0; i < IRIS_HWC_MAX_LAYERS; ++i) {
        if (d->layers[i].used && d->layers[i].changed) {
            if (out_layers && out_types && count < cap) {
                out_layers[count] = d->layers[i].id;
                out_types[count] = HWC2_COMPOSITION_CLIENT;
            }
            ++count;
        }
    }
    *out_num = (out_layers && count > cap) ? cap : count;
    pthread_mutex_unlock(&d->lock);
    return HWC2_ERROR_NONE;
}

static int32_t iris_get_client_target_support(hwc2_device_t* device, hwc2_display_t display,
                                              uint32_t width, uint32_t height,
                                              int32_t format, int32_t dataspace) {
    (void)device;
    (void)display;
    (void)width;
    (void)height;
    (void)format;
    (void)dataspace;
    return HWC2_ERROR_NONE;
}

static int32_t iris_get_color_modes(hwc2_device_t* device, hwc2_display_t display,
                                    uint32_t* out_num, int32_t* out_modes) {
    (void)device;
    (void)display;
    if (!out_modes) {
        *out_num = 1;
        return HWC2_ERROR_NONE;
    }
    if (*out_num >= 1) {
        out_modes[0] = 0; /* HAL_COLOR_MODE_NATIVE */
        *out_num = 1;
    }
    return HWC2_ERROR_NONE;
}

static int32_t iris_get_display_attribute(hwc2_device_t* device, hwc2_display_t display,
                                          hwc2_config_t config, int32_t attribute,
                                          int32_t* out_value) {
    (void)device;
    (void)display;
    if (config != 0) return HWC2_ERROR_BAD_CONFIG;
    switch (attribute) {
        case HWC2_ATTRIBUTE_WIDTH: *out_value = IRIS_HWC_WIDTH; return HWC2_ERROR_NONE;
        case HWC2_ATTRIBUTE_HEIGHT: *out_value = IRIS_HWC_HEIGHT; return HWC2_ERROR_NONE;
        case HWC2_ATTRIBUTE_VSYNC_PERIOD: *out_value = (int32_t)IRIS_HWC_VSYNC_NS; return HWC2_ERROR_NONE;
        case HWC2_ATTRIBUTE_DPI_X: *out_value = IRIS_HWC_DPI; return HWC2_ERROR_NONE;
        case HWC2_ATTRIBUTE_DPI_Y: *out_value = IRIS_HWC_DPI; return HWC2_ERROR_NONE;
        case 7: /* HWC2_ATTRIBUTE_CONFIG_GROUP */ *out_value = 0; return HWC2_ERROR_NONE;
        default: return HWC2_ERROR_BAD_PARAMETER;
    }
}

static int32_t iris_get_display_configs(hwc2_device_t* device, hwc2_display_t display,
                                        uint32_t* out_num, hwc2_config_t* out_configs) {
    (void)device;
    (void)display;
    if (!out_configs) {
        *out_num = 1;
        return HWC2_ERROR_NONE;
    }
    if (*out_num >= 1) {
        out_configs[0] = 0;
        *out_num = 1;
    }
    return HWC2_ERROR_NONE;
}

static int32_t iris_get_display_name(hwc2_device_t* device, hwc2_display_t display,
                                     uint32_t* out_size, char* out_name) {
    (void)device;
    (void)display;
    static const char name[] = "IrisVirtual";
    uint32_t len = (uint32_t)(sizeof(name) - 1);
    if (!out_name) {
        *out_size = len;
        return HWC2_ERROR_NONE;
    }
    uint32_t n = (*out_size < len) ? *out_size : len;
    memcpy(out_name, name, n);
    *out_size = n;
    return HWC2_ERROR_NONE;
}

static int32_t iris_get_display_requests(hwc2_device_t* device, hwc2_display_t display,
                                         int32_t* out_display_requests, uint32_t* out_num,
                                         hwc2_layer_t* out_layers, int32_t* out_layer_requests) {
    (void)device;
    (void)display;
    (void)out_layers;
    (void)out_layer_requests;
    *out_display_requests = 0;
    *out_num = 0;
    return HWC2_ERROR_NONE;
}

static int32_t iris_get_display_type(hwc2_device_t* device, hwc2_display_t display,
                                     int32_t* out_type) {
    (void)device;
    *out_type = (display == 0) ? HWC2_DISPLAY_TYPE_PHYSICAL : HWC2_DISPLAY_TYPE_VIRTUAL;
    return HWC2_ERROR_NONE;
}

static int32_t iris_get_doze_support(hwc2_device_t* device, hwc2_display_t display,
                                     int32_t* out_support) {
    (void)device;
    (void)display;
    *out_support = 0;
    return HWC2_ERROR_NONE;
}

static int32_t iris_get_hdr_capabilities(hwc2_device_t* device, hwc2_display_t display,
                                         uint32_t* out_num, int32_t* out_types,
                                         float* out_max_lum, float* out_max_avg_lum,
                                         float* out_min_lum) {
    (void)device;
    (void)display;
    (void)out_types;
    *out_num = 0;
    *out_max_lum = 0.0f;
    *out_max_avg_lum = 0.0f;
    *out_min_lum = 0.0f;
    return HWC2_ERROR_NONE;
}

static int32_t iris_get_release_fences(hwc2_device_t* device, hwc2_display_t display,
                                       uint32_t* out_num, hwc2_layer_t* out_layers,
                                       int32_t* out_fences) {
    (void)device;
    (void)display;
    (void)out_layers;
    (void)out_fences;
    *out_num = 0;
    return HWC2_ERROR_NONE;
}

static int32_t iris_present_display(hwc2_device_t* device, hwc2_display_t display,
                                    int32_t* out_present_fence) {
    (void)device;
    (void)display;
    *out_present_fence = -1;
    return HWC2_ERROR_NONE;
}

static int32_t iris_set_active_config(hwc2_device_t* device, hwc2_display_t display,
                                      hwc2_config_t config) {
    (void)device;
    (void)display;
    return (config == 0) ? HWC2_ERROR_NONE : HWC2_ERROR_BAD_CONFIG;
}

static int32_t iris_set_client_target(hwc2_device_t* device, hwc2_display_t display,
                                      buffer_handle_t target, int32_t acquire_fence,
                                      int32_t dataspace, hwc_region_t damage) {
    (void)device;
    (void)display;
    (void)target;
    (void)dataspace;
    (void)damage;
    if (acquire_fence >= 0) close(acquire_fence);
    return HWC2_ERROR_NONE;
}

static int32_t iris_set_color_mode(hwc2_device_t* device, hwc2_display_t display, int32_t mode) {
    (void)device;
    (void)display;
    (void)mode;
    return HWC2_ERROR_NONE;
}

static int32_t iris_set_color_transform(hwc2_device_t* device, hwc2_display_t display,
                                        const float* matrix, int32_t hint) {
    (void)device;
    (void)display;
    (void)matrix;
    (void)hint;
    return HWC2_ERROR_NONE;
}

static int32_t iris_set_output_buffer(hwc2_device_t* device, hwc2_display_t display,
                                      buffer_handle_t buffer, int32_t release_fence) {
    (void)device;
    (void)display;
    (void)buffer;
    if (release_fence >= 0) close(release_fence);
    return HWC2_ERROR_NONE;
}

static int32_t iris_set_power_mode(hwc2_device_t* device, hwc2_display_t display, int32_t mode) {
    (void)device;
    (void)display;
    (void)mode;
    return HWC2_ERROR_NONE;
}

static int32_t iris_set_vsync_enabled(hwc2_device_t* device, hwc2_display_t display,
                                      int32_t enabled) {
    (void)display;
    struct iris_hwc2_dev* d = to_dev(device);
    pthread_mutex_lock(&d->lock);
    d->vsync_enabled = (enabled == HWC2_VSYNC_ENABLE);
    pthread_mutex_unlock(&d->lock);
    return HWC2_ERROR_NONE;
}

static int32_t iris_validate_display(hwc2_device_t* device, hwc2_display_t display,
                                     uint32_t* out_num_types, uint32_t* out_num_requests) {
    struct iris_hwc2_dev* d = to_dev(device);
    pthread_mutex_lock(&d->lock);
    if (!valid_display(d, display)) {
        pthread_mutex_unlock(&d->lock);
        return HWC2_ERROR_BAD_DISPLAY;
    }
    uint32_t changed = 0;
    for (int i = 0; i < IRIS_HWC_MAX_LAYERS; ++i) {
        if (d->layers[i].used && d->layers[i].comp_type != HWC2_COMPOSITION_CLIENT) {
            d->layers[i].changed = 1;
            ++changed;
        } else {
            d->layers[i].changed = 0;
        }
    }
    *out_num_types = changed;
    *out_num_requests = 0;
    pthread_mutex_unlock(&d->lock);
    return changed ? HWC2_ERROR_HAS_CHANGES : HWC2_ERROR_NONE;
}

static int32_t iris_set_cursor_position(hwc2_device_t* d, hwc2_display_t disp,
                                        hwc2_layer_t l, int32_t x, int32_t y) {
    (void)d; (void)disp; (void)l; (void)x; (void)y;
    return HWC2_ERROR_NONE;
}

static int32_t iris_set_layer_buffer(hwc2_device_t* d, hwc2_display_t disp,
                                     hwc2_layer_t l, buffer_handle_t buf, int32_t fence) {
    (void)d; (void)disp; (void)l; (void)buf;
    if (fence >= 0) close(fence);
    return HWC2_ERROR_NONE;
}

static int32_t iris_set_layer_surface_damage(hwc2_device_t* d, hwc2_display_t disp,
                                             hwc2_layer_t l, hwc_region_t damage) {
    (void)d; (void)disp; (void)l; (void)damage;
    return HWC2_ERROR_NONE;
}

static int32_t iris_set_layer_blend_mode(hwc2_device_t* d, hwc2_display_t disp,
                                         hwc2_layer_t l, int32_t mode) {
    (void)d; (void)disp; (void)l; (void)mode;
    return HWC2_ERROR_NONE;
}

static int32_t iris_set_layer_color(hwc2_device_t* d, hwc2_display_t disp,
                                    hwc2_layer_t l, hwc_color_t color) {
    (void)d; (void)disp; (void)l; (void)color;
    return HWC2_ERROR_NONE;
}

static int32_t iris_set_layer_composition_type(hwc2_device_t* device, hwc2_display_t display,
                                               hwc2_layer_t layer, int32_t type) {
    (void)display;
    struct iris_hwc2_dev* d = to_dev(device);
    pthread_mutex_lock(&d->lock);
    for (int i = 0; i < IRIS_HWC_MAX_LAYERS; ++i) {
        if (d->layers[i].used && d->layers[i].id == layer) {
            d->layers[i].comp_type = type;
            pthread_mutex_unlock(&d->lock);
            return HWC2_ERROR_NONE;
        }
    }
    pthread_mutex_unlock(&d->lock);
    return HWC2_ERROR_BAD_LAYER;
}

static int32_t iris_set_layer_dataspace(hwc2_device_t* d, hwc2_display_t disp,
                                        hwc2_layer_t l, int32_t ds) {
    (void)d; (void)disp; (void)l; (void)ds;
    return HWC2_ERROR_NONE;
}

static int32_t iris_set_layer_display_frame(hwc2_device_t* d, hwc2_display_t disp,
                                            hwc2_layer_t l, hwc_rect_t frame) {
    (void)d; (void)disp; (void)l; (void)frame;
    return HWC2_ERROR_NONE;
}

static int32_t iris_set_layer_plane_alpha(hwc2_device_t* d, hwc2_display_t disp,
                                          hwc2_layer_t l, float alpha) {
    (void)d; (void)disp; (void)l; (void)alpha;
    return HWC2_ERROR_NONE;
}

static int32_t iris_set_layer_source_crop(hwc2_device_t* d, hwc2_display_t disp,
                                          hwc2_layer_t l, hwc_frect_t crop) {
    (void)d; (void)disp; (void)l; (void)crop;
    return HWC2_ERROR_NONE;
}

static int32_t iris_set_layer_transform(hwc2_device_t* d, hwc2_display_t disp,
                                        hwc2_layer_t l, int32_t tr) {
    (void)d; (void)disp; (void)l; (void)tr;
    return HWC2_ERROR_NONE;
}

static int32_t iris_set_layer_visible_region(hwc2_device_t* d, hwc2_display_t disp,
                                             hwc2_layer_t l, hwc_region_t visible) {
    (void)d; (void)disp; (void)l; (void)visible;
    return HWC2_ERROR_NONE;
}

static int32_t iris_set_layer_z_order(hwc2_device_t* d, hwc2_display_t disp,
                                      hwc2_layer_t l, uint32_t z) {
    (void)d; (void)disp; (void)l; (void)z;
    return HWC2_ERROR_NONE;
}

static void iris_get_capabilities(hwc2_device_t* device, uint32_t* out_count,
                                  int32_t* out_capabilities) {
    (void)device;
    (void)out_capabilities;
    *out_count = 0;
}

static hwc2_function_pointer_t iris_get_function(hwc2_device_t* device, int32_t descriptor) {
    (void)device;
    switch (descriptor) {
        case HWC2_FUNCTION_ACCEPT_DISPLAY_CHANGES: return (hwc2_function_pointer_t)iris_accept_display_changes;
        case HWC2_FUNCTION_CREATE_LAYER: return (hwc2_function_pointer_t)iris_create_layer;
        case HWC2_FUNCTION_CREATE_VIRTUAL_DISPLAY: return (hwc2_function_pointer_t)iris_create_virtual_display;
        case HWC2_FUNCTION_DESTROY_LAYER: return (hwc2_function_pointer_t)iris_destroy_layer;
        case HWC2_FUNCTION_DESTROY_VIRTUAL_DISPLAY: return (hwc2_function_pointer_t)iris_destroy_virtual_display;
        case HWC2_FUNCTION_DUMP: return (hwc2_function_pointer_t)iris_dump;
        case HWC2_FUNCTION_GET_ACTIVE_CONFIG: return (hwc2_function_pointer_t)iris_get_active_config;
        case HWC2_FUNCTION_GET_CHANGED_COMPOSITION_TYPES: return (hwc2_function_pointer_t)iris_get_changed_composition_types;
        case HWC2_FUNCTION_GET_CLIENT_TARGET_SUPPORT: return (hwc2_function_pointer_t)iris_get_client_target_support;
        case HWC2_FUNCTION_GET_COLOR_MODES: return (hwc2_function_pointer_t)iris_get_color_modes;
        case HWC2_FUNCTION_GET_DISPLAY_ATTRIBUTE: return (hwc2_function_pointer_t)iris_get_display_attribute;
        case HWC2_FUNCTION_GET_DISPLAY_CONFIGS: return (hwc2_function_pointer_t)iris_get_display_configs;
        case HWC2_FUNCTION_GET_DISPLAY_NAME: return (hwc2_function_pointer_t)iris_get_display_name;
        case HWC2_FUNCTION_GET_DISPLAY_REQUESTS: return (hwc2_function_pointer_t)iris_get_display_requests;
        case HWC2_FUNCTION_GET_DISPLAY_TYPE: return (hwc2_function_pointer_t)iris_get_display_type;
        case HWC2_FUNCTION_GET_DOZE_SUPPORT: return (hwc2_function_pointer_t)iris_get_doze_support;
        case HWC2_FUNCTION_GET_HDR_CAPABILITIES: return (hwc2_function_pointer_t)iris_get_hdr_capabilities;
        case HWC2_FUNCTION_GET_MAX_VIRTUAL_DISPLAY_COUNT: return (hwc2_function_pointer_t)iris_get_max_virtual_display_count;
        case HWC2_FUNCTION_GET_RELEASE_FENCES: return (hwc2_function_pointer_t)iris_get_release_fences;
        case HWC2_FUNCTION_PRESENT_DISPLAY: return (hwc2_function_pointer_t)iris_present_display;
        case HWC2_FUNCTION_REGISTER_CALLBACK: return (hwc2_function_pointer_t)iris_register_callback;
        case HWC2_FUNCTION_SET_ACTIVE_CONFIG: return (hwc2_function_pointer_t)iris_set_active_config;
        case HWC2_FUNCTION_SET_CLIENT_TARGET: return (hwc2_function_pointer_t)iris_set_client_target;
        case HWC2_FUNCTION_SET_COLOR_MODE: return (hwc2_function_pointer_t)iris_set_color_mode;
        case HWC2_FUNCTION_SET_COLOR_TRANSFORM: return (hwc2_function_pointer_t)iris_set_color_transform;
        case HWC2_FUNCTION_SET_CURSOR_POSITION: return (hwc2_function_pointer_t)iris_set_cursor_position;
        case HWC2_FUNCTION_SET_LAYER_BLEND_MODE: return (hwc2_function_pointer_t)iris_set_layer_blend_mode;
        case HWC2_FUNCTION_SET_LAYER_BUFFER: return (hwc2_function_pointer_t)iris_set_layer_buffer;
        case HWC2_FUNCTION_SET_LAYER_COLOR: return (hwc2_function_pointer_t)iris_set_layer_color;
        case HWC2_FUNCTION_SET_LAYER_COMPOSITION_TYPE: return (hwc2_function_pointer_t)iris_set_layer_composition_type;
        case HWC2_FUNCTION_SET_LAYER_DATASPACE: return (hwc2_function_pointer_t)iris_set_layer_dataspace;
        case HWC2_FUNCTION_SET_LAYER_DISPLAY_FRAME: return (hwc2_function_pointer_t)iris_set_layer_display_frame;
        case HWC2_FUNCTION_SET_LAYER_PLANE_ALPHA: return (hwc2_function_pointer_t)iris_set_layer_plane_alpha;
        case HWC2_FUNCTION_SET_LAYER_SOURCE_CROP: return (hwc2_function_pointer_t)iris_set_layer_source_crop;
        case HWC2_FUNCTION_SET_LAYER_SURFACE_DAMAGE: return (hwc2_function_pointer_t)iris_set_layer_surface_damage;
        case HWC2_FUNCTION_SET_LAYER_TRANSFORM: return (hwc2_function_pointer_t)iris_set_layer_transform;
        case HWC2_FUNCTION_SET_LAYER_VISIBLE_REGION: return (hwc2_function_pointer_t)iris_set_layer_visible_region;
        case HWC2_FUNCTION_SET_LAYER_Z_ORDER: return (hwc2_function_pointer_t)iris_set_layer_z_order;
        case HWC2_FUNCTION_SET_OUTPUT_BUFFER: return (hwc2_function_pointer_t)iris_set_output_buffer;
        case HWC2_FUNCTION_SET_POWER_MODE: return (hwc2_function_pointer_t)iris_set_power_mode;
        case HWC2_FUNCTION_SET_VSYNC_ENABLED: return (hwc2_function_pointer_t)iris_set_vsync_enabled;
        case HWC2_FUNCTION_VALIDATE_DISPLAY: return (hwc2_function_pointer_t)iris_validate_display;
        default: return NULL;
    }
}

static int iris_close(struct hw_device_t* dev) {
    struct iris_hwc2_dev* d = (struct iris_hwc2_dev*)dev;
    pthread_mutex_lock(&d->lock);
    d->stop = 1;
    int started = d->vsync_thread_started;
    pthread_t th = d->vsync_thread;
    pthread_mutex_unlock(&d->lock);
    if (started) pthread_join(th, NULL);
    pthread_mutex_destroy(&d->lock);
    free(d);
    return 0;
}

static int iris_open(const struct hw_module_t* module, const char* name,
                     struct hw_device_t** device) {
    if (strcmp(name, HWC_HARDWARE_COMPOSER) != 0) return -EINVAL;
    struct iris_hwc2_dev* d = (struct iris_hwc2_dev*)calloc(1, sizeof(*d));
    if (!d) return -ENOMEM;
    pthread_mutex_init(&d->lock, NULL);
    d->base.common.tag = HARDWARE_DEVICE_TAG;
    d->base.common.version = HWC_DEVICE_API_VERSION_2_0;
    d->base.common.module = (struct hw_module_t*)module;
    d->base.common.close = iris_close;
    d->base.getCapabilities = iris_get_capabilities;
    d->base.getFunction = iris_get_function;
    *device = &d->base.common;
    return 0;
}

static struct hw_module_methods_t iris_module_methods = {
    .open = iris_open,
};

struct hw_module_t HAL_MODULE_INFO_SYM = {
    .tag = HARDWARE_MODULE_TAG,
    .module_api_version = HWC_MODULE_API_VERSION_0_1,
    .hal_api_version = HARDWARE_HAL_API_VERSION,
    .id = HWC_HARDWARE_MODULE_ID,
    .name = "IrisBox Virtual HWC2 Module",
    .author = "IrisBox",
    .methods = &iris_module_methods,
};
