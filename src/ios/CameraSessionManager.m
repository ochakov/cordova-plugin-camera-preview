#include "CameraSessionManager.h"

// Static variables to persist focal length state across view controller recreation
static float savedFocalLength = -1.0f;
static NSInteger savedFocalLengthIndex = -1;
static BOOL hasRestoredFromSavedState = NO;

@implementation CameraSessionManager

- (CameraSessionManager *)init {
  if (self = [super init]) {
    // Create the AVCaptureSession
    self.session = [[AVCaptureSession alloc] init];
    self.sessionQueue = dispatch_queue_create("session queue", DISPATCH_QUEUE_SERIAL);
    if ([self.session canSetSessionPreset:AVCaptureSessionPresetPhoto]) {
      [self.session setSessionPreset:AVCaptureSessionPresetPhoto];
    }
    self.filterLock = [[NSLock alloc] init];
    self.focalLengthToCameraDevice = [[NSMutableDictionary alloc] init];
    self.availableCameraDevices = [[NSMutableArray alloc] init];
    self.currentFocalLengthIndex = 0;

    TemperatureAndTint * wbIncandescent = [[TemperatureAndTint alloc] init];
    wbIncandescent.mode = @"incandescent";
    wbIncandescent.minTemperature = 2200;
    wbIncandescent.maxTemperature = 3200;
    wbIncandescent.tint = 0;

    TemperatureAndTint * wbCloudyDaylight = [[TemperatureAndTint alloc] init];
    wbCloudyDaylight.mode = @"cloudy-daylight";
    wbCloudyDaylight.minTemperature = 6000;
    wbCloudyDaylight.maxTemperature = 7000;
    wbCloudyDaylight.tint = 0;

    TemperatureAndTint * wbDaylight = [[TemperatureAndTint alloc] init];
    wbDaylight.mode = @"daylight";
    wbDaylight.minTemperature = 5500;
    wbDaylight.maxTemperature = 5800;
    wbDaylight.tint = 0;

    TemperatureAndTint * wbFluorescent = [[TemperatureAndTint alloc] init];
    wbFluorescent.mode = @"fluorescent";
    wbFluorescent.minTemperature = 3300;
    wbFluorescent.maxTemperature = 3800;
    wbFluorescent.tint = 0;

    TemperatureAndTint * wbShade = [[TemperatureAndTint alloc] init];
    wbShade.mode = @"shade";
    wbShade.minTemperature = 7000;
    wbShade.maxTemperature = 8000;
    wbShade.tint = 0;

    TemperatureAndTint * wbWarmFluorescent = [[TemperatureAndTint alloc] init];
    wbWarmFluorescent.mode = @"warm-fluorescent";
    wbWarmFluorescent.minTemperature = 3000;
    wbWarmFluorescent.maxTemperature = 3000;
    wbWarmFluorescent.tint = 0;

    TemperatureAndTint * wbTwilight = [[TemperatureAndTint alloc] init];
    wbTwilight.mode = @"twilight";
    wbTwilight.minTemperature = 4000;
    wbTwilight.maxTemperature = 4400;
    wbTwilight.tint = 0;

    self.colorTemperatures = [NSDictionary dictionaryWithObjects:@[wbIncandescent,wbCloudyDaylight,wbDaylight,wbFluorescent,wbShade,wbWarmFluorescent,wbTwilight]
                                           forKeys:@[@"incandescent",@"cloudy-daylight",@"daylight",@"fluorescent",@"shade",@"warm-fluorescent",@"twilight"]];
  }
  return self;
}

- (NSArray *) getDeviceFormats {
  AVCaptureDevice * videoDevice = [self cameraWithPosition: self.defaultCamera];

  return videoDevice.formats;
}

- (AVCaptureVideoOrientation) getCurrentOrientation {
  return [self getCurrentOrientation: [[UIApplication sharedApplication] statusBarOrientation]];
}

- (AVCaptureVideoOrientation) getCurrentOrientation:(UIInterfaceOrientation)toInterfaceOrientation {
  AVCaptureVideoOrientation orientation;
  switch (toInterfaceOrientation) {
    case UIInterfaceOrientationPortraitUpsideDown:
      orientation = AVCaptureVideoOrientationPortraitUpsideDown;
      break;
    case UIInterfaceOrientationLandscapeRight:
      orientation = AVCaptureVideoOrientationLandscapeRight;
      break;
    case UIInterfaceOrientationLandscapeLeft:
      orientation = AVCaptureVideoOrientationLandscapeLeft;
      break;
    default:
        case UIInterfaceOrientationPortrait:
      orientation = AVCaptureVideoOrientationPortrait;
  }
  return orientation;
}

- (void) setupSession:(NSString *)defaultCamera completion:(void(^)(BOOL started))completion{
  // If this fails, video input will just stream blank frames and the user will be notified. User only has to accept once.
  [self checkDeviceAuthorizationStatus];

  dispatch_async(self.sessionQueue, ^{
      NSError *error = nil;
      BOOL success = TRUE;

      NSLog(@"defaultCamera: %@", defaultCamera);
      if ([defaultCamera isEqual: @"front"]) {
        self.defaultCamera = AVCaptureDevicePositionFront;
      } else {
        self.defaultCamera = AVCaptureDevicePositionBack;
      }

      AVCaptureDevice * videoDevice = [self cameraWithPosition: self.defaultCamera];

      if ([videoDevice hasFlash] && [videoDevice isFlashModeSupported:AVCaptureFlashModeAuto]) {
        if ([videoDevice lockForConfiguration:&error]) {
          [videoDevice setFlashMode:AVCaptureFlashModeAuto];
          [videoDevice unlockForConfiguration];
        } else {
          NSLog(@"%@", error);
          success = FALSE;
        }
      }

      AVCaptureDeviceInput *videoDeviceInput = [AVCaptureDeviceInput deviceInputWithDevice:videoDevice error:&error];

      if (error) {
        NSLog(@"%@", error);
        success = FALSE;
      }

      if ([self.session canAddInput:videoDeviceInput]) {
        [self.session addInput:videoDeviceInput];
        self.videoDeviceInput = videoDeviceInput;
      }

      AVCaptureStillImageOutput *stillImageOutput = [[AVCaptureStillImageOutput alloc] init];
      if ([self.session canAddOutput:stillImageOutput]) {
        [self.session addOutput:stillImageOutput];
        [stillImageOutput setOutputSettings:@{AVVideoCodecKey : AVVideoCodecJPEG}];
        self.stillImageOutput = stillImageOutput;
      }

      AVCaptureVideoDataOutput *dataOutput = [[AVCaptureVideoDataOutput alloc] init];
      if ([self.session canAddOutput:dataOutput]) {
        self.dataOutput = dataOutput;
        [dataOutput setAlwaysDiscardsLateVideoFrames:YES];
        [dataOutput setVideoSettings:[NSDictionary dictionaryWithObject:[NSNumber numberWithInt:kCVPixelFormatType_32BGRA] forKey:(id)kCVPixelBufferPixelFormatTypeKey]];

        [dataOutput setSampleBufferDelegate:self.delegate queue:self.sessionQueue];

        [self.session addOutput:dataOutput];
      }

      [self updateOrientation:[self getCurrentOrientation]];
      self.device = videoDevice;

      // Initialize available focal lengths for the camera
      [self initializeFocalLengths];

      completion(success);
  });
}

- (void) updateOrientation:(AVCaptureVideoOrientation)orientation {
  AVCaptureConnection *captureConnection;
  if (self.stillImageOutput != nil) {
    captureConnection = [self.stillImageOutput connectionWithMediaType:AVMediaTypeVideo];
    if ([captureConnection isVideoOrientationSupported]) {
      [captureConnection setVideoOrientation:orientation];
    }
  }
  if (self.dataOutput != nil) {
    captureConnection = [self.dataOutput connectionWithMediaType:AVMediaTypeVideo];
    if ([captureConnection isVideoOrientationSupported]) {
      [captureConnection setVideoOrientation:orientation];
    }
  }
}

- (void) switchCamera:(void(^)(BOOL switched))completion {
  if (self.defaultCamera == AVCaptureDevicePositionFront) {
    self.defaultCamera = AVCaptureDevicePositionBack;
  } else {
    self.defaultCamera = AVCaptureDevicePositionFront;
  }

  dispatch_async([self sessionQueue], ^{
      NSError *error = nil;
      BOOL success = TRUE;

      [self.session beginConfiguration];

      if (self.videoDeviceInput != nil) {
        [self.session removeInput:[self videoDeviceInput]];
        [self setVideoDeviceInput:nil];
      }

      AVCaptureDevice *videoDevice = nil;

      videoDevice = [self cameraWithPosition:self.defaultCamera];

      if ([videoDevice hasFlash] && [videoDevice isFlashModeSupported:self.defaultFlashMode]) {
        if ([videoDevice lockForConfiguration:&error]) {
          [videoDevice setFlashMode:self.defaultFlashMode];
          [videoDevice unlockForConfiguration];
        } else {
          NSLog(@"%@", error);
          success = FALSE;
        }
      }

      AVCaptureDeviceInput *videoDeviceInput = [AVCaptureDeviceInput deviceInputWithDevice:videoDevice error:&error];

      if (error) {
        NSLog(@"%@", error);
        success = FALSE;
      }

      if ([self.session canAddInput:videoDeviceInput]) {
        [self.session addInput:videoDeviceInput];
        [self setVideoDeviceInput:videoDeviceInput];
      }

      [self updateOrientation:[self getCurrentOrientation]];
      [self.session commitConfiguration];
      self.device = videoDevice;

      // Recalculate available focal lengths for the new camera
      [self initializeFocalLengths];

      completion(success);
  });
}

- (void) switchFocalLength:(void(^)(BOOL switched))completion {
  if (self.availableFocalLengths == nil || [self.availableFocalLengths count] <= 1) {
    NSLog(@"Cannot switch focal length: no focal lengths available or only one focal length");
    completion(FALSE);
    return;
  }

  // First, ensure currentFocalLengthIndex is correctly set to the current device
  [self updateCurrentFocalLengthIndex];

  // Cycle to next focal length
  self.currentFocalLengthIndex = (self.currentFocalLengthIndex + 1) % [self.availableFocalLengths count];
  NSNumber *targetFocalLength = [self.availableFocalLengths objectAtIndex:self.currentFocalLengthIndex];

  NSLog(@"Switching to focal length: %@mm (index: %ld)", targetFocalLength, (long)self.currentFocalLengthIndex);

  [self switchToCameraDeviceForFocalLength:targetFocalLength completion:completion];
}

- (void) switchFocalLength:(float)focalLength completion:(void(^)(BOOL switched))completion {
  if (self.availableFocalLengths == nil || [self.availableFocalLengths count] == 0) {
    NSLog(@"Cannot switch focal length: no focal lengths available");
    completion(FALSE);
    return;
  }

  // First, ensure currentFocalLengthIndex is correctly set to the current device
  [self updateCurrentFocalLengthIndex];

  // Find the closest available focal length
  NSInteger closestIndex = 0;
  float minDifference = FLT_MAX;

  for (NSInteger i = 0; i < [self.availableFocalLengths count]; i++) {
    NSNumber *availableFocalLength = [self.availableFocalLengths objectAtIndex:i];
    float difference = fabsf([availableFocalLength floatValue] - focalLength);
    if (difference < minDifference) {
      minDifference = difference;
      closestIndex = i;
    }
  }

  self.currentFocalLengthIndex = closestIndex;
  NSNumber *targetFocalLength = [self.availableFocalLengths objectAtIndex:closestIndex];

  NSLog(@"Switching to focal length: %@mm (index: %ld)", targetFocalLength, (long)self.currentFocalLengthIndex);

  [self switchToCameraDeviceForFocalLength:targetFocalLength completion:completion];
}

- (NSArray *) getAvailableFocalLengths {
  if (self.availableFocalLengths == nil) {
    [self initializeFocalLengths];
  }
  return self.availableFocalLengths;
}

// Helper method to update currentFocalLengthIndex based on the current device
- (void) updateCurrentFocalLengthIndex {
  if (self.device == nil || self.availableFocalLengths == nil) {
    return;
  }

  // Find which focal length corresponds to the current device
  for (NSInteger i = 0; i < [self.availableFocalLengths count]; i++) {
    NSNumber *focalLength = [self.availableFocalLengths objectAtIndex:i];
    AVCaptureDevice *mappedDevice = [self.focalLengthToCameraDevice objectForKey:focalLength];

    if (mappedDevice != nil && [mappedDevice.uniqueID isEqualToString:self.device.uniqueID]) {
      NSLog(@"Updated currentFocalLengthIndex from %ld to %ld (focal length: %@mm)",
            (long)self.currentFocalLengthIndex, (long)i, focalLength);
      self.currentFocalLengthIndex = i;
      return;
    }
  }

  NSLog(@"Warning: Could not find current device in focal length mapping");
}

- (float) getCurrentFocalLength {
  if (self.availableFocalLengths != nil && self.currentFocalLengthIndex >= 0 && self.currentFocalLengthIndex < [self.availableFocalLengths count]) {
    NSNumber *focalLength = [self.availableFocalLengths objectAtIndex:self.currentFocalLengthIndex];
    return [focalLength floatValue];
  }
  return 0.0f;
}

- (void) setFocalLength:(float)focalLength {
  if (self.availableFocalLengths == nil) {
    [self initializeFocalLengths];
  }

  // Find the closest available focal length
  NSInteger closestIndex = 0;
  float minDifference = FLT_MAX;

  for (NSInteger i = 0; i < [self.availableFocalLengths count]; i++) {
    NSNumber *availableFocalLength = [self.availableFocalLengths objectAtIndex:i];
    float difference = fabsf([availableFocalLength floatValue] - focalLength);
    if (difference < minDifference) {
      minDifference = difference;
      closestIndex = i;
    }
  }

  self.currentFocalLengthIndex = closestIndex;
  NSNumber *targetFocalLength = [self.availableFocalLengths objectAtIndex:closestIndex];

  dispatch_async([self sessionQueue], ^{
    NSError *error = nil;
    [self.device lockForConfiguration:&error];
    if (!error) {
      // Calculate zoom ratio based on focal length
      NSNumber *defaultFocalLength = [self.availableFocalLengths objectAtIndex:0];
      CGFloat zoomRatio = [targetFocalLength floatValue] / [defaultFocalLength floatValue];

      // Apply zoom using existing setZoom method
      self.videoZoomFactor = MAX(1.0, MIN(zoomRatio, self.device.activeFormat.videoMaxZoomFactor));
      [self.device setVideoZoomFactor:self.videoZoomFactor];

      [self.device unlockForConfiguration];
    }
  });
}

- (void) saveFocalLengthState {
  // Save focal length state to static variables to preserve it during rotation
  // Static variables survive view controller recreation
  if (self.availableFocalLengths != nil && self.currentFocalLengthIndex >= 0 && self.currentFocalLengthIndex < [self.availableFocalLengths count]) {
    NSNumber *focalLength = [self.availableFocalLengths objectAtIndex:self.currentFocalLengthIndex];
    savedFocalLength = [focalLength floatValue];
    savedFocalLengthIndex = self.currentFocalLengthIndex;
    NSLog(@"saveFocalLengthState - Saved focal length: %@mm (index: %ld)", focalLength, (long)savedFocalLengthIndex);
  }
}

- (void) initializeFocalLengths {
  if (self.device == nil) {
    return;
  }

  // Determine the previous focal length to restore
  // Only restore if availableFocalLengths is nil (indicating view controller recreation during rotation)
  // AND we haven't already restored from saved state in this rotation cycle
  NSNumber *previousFocalLength = nil;
  BOOL isViewControllerRecreation = (self.availableFocalLengths == nil);

  // Check if we have a saved focal length from rotation AND this is a view controller recreation
  // AND we haven't already restored from saved state
  if (isViewControllerRecreation && savedFocalLength > 0 && !hasRestoredFromSavedState) {
    previousFocalLength = [NSNumber numberWithFloat:savedFocalLength];
    hasRestoredFromSavedState = YES;
    NSLog(@"initializeFocalLengths - Using saved focal length from rotation: %@mm", previousFocalLength);
  } else if (!isViewControllerRecreation && self.availableFocalLengths != nil && self.currentFocalLengthIndex >= 0 && self.currentFocalLengthIndex < [self.availableFocalLengths count]) {
    // If NOT a view controller recreation, preserve the current focal length
    previousFocalLength = [self.availableFocalLengths objectAtIndex:self.currentFocalLengthIndex];
    NSLog(@"initializeFocalLengths - Preserving current focal length: %@mm (index: %ld)", previousFocalLength, (long)self.currentFocalLengthIndex);
  } else {
    NSLog(@"initializeFocalLengths - First initialization or view controller recreation, no previous focal length to restore");
  }

  [self.focalLengthToCameraDevice removeAllObjects];
  [self.availableCameraDevices removeAllObjects];

  NSMutableArray *focalLengths = [[NSMutableArray alloc] init];

  // Use AVCaptureDeviceDiscoverySession to find all camera devices
  AVCaptureDeviceDiscoverySession *discoverySession = nil;

  if (@available(iOS 13.0, *)) {
    // iOS 13+ API
    NSArray *deviceTypes = @[
      AVCaptureDeviceTypeBuiltInWideAngleCamera,
      AVCaptureDeviceTypeBuiltInTelephotoCamera,
      AVCaptureDeviceTypeBuiltInUltraWideCamera
    ];

    discoverySession = [AVCaptureDeviceDiscoverySession discoverySessionWithDeviceTypes:deviceTypes
                                                                                mediaType:AVMediaTypeVideo
                                                                                 position:self.defaultCamera];
  } else {
    // Fallback for older iOS versions
    NSArray *devices = [AVCaptureDevice devicesWithMediaType:AVMediaTypeVideo];
    discoverySession = nil; // Will use fallback below
  }

  NSArray *devices = discoverySession ? discoverySession.devices : [AVCaptureDevice devicesWithMediaType:AVMediaTypeVideo];

  // Define focal lengths for each camera type
  NSDictionary *cameraTypeFocalLengths = @{
    AVCaptureDeviceTypeBuiltInWideAngleCamera: @1.0f,
    AVCaptureDeviceTypeBuiltInTelephotoCamera: @2.0f,
    AVCaptureDeviceTypeBuiltInUltraWideCamera: @0.5f
  };

  // Collect available camera devices with their focal lengths
  for (AVCaptureDevice *device in devices) {
    if ([device position] == self.defaultCamera) {
      NSString *deviceType = device.deviceType;
      NSNumber *focalLength = cameraTypeFocalLengths[deviceType];

      if (focalLength) {
        [focalLengths addObject:focalLength];
        [self.focalLengthToCameraDevice setObject:device forKey:focalLength];
        [self.availableCameraDevices addObject:device];

        NSLog(@"Found camera device: %@ with focal length: %@mm", deviceType, focalLength);
      }
    }
  }

  // If no devices found with discovery session, use fallback
  if ([focalLengths count] == 0) {
    NSLog(@"No multi-camera setup found, using single camera with virtual focal lengths");

    // Fallback: Create virtual focal lengths based on zoom capabilities
    CGFloat maxZoom = self.device.activeFormat.videoMaxZoomFactor;
    NSInteger numFocalLengths = MIN(3, (NSInteger)maxZoom);

    for (NSInteger i = 0; i < numFocalLengths; i++) {
      CGFloat zoomFactor = 1.0 + (i * (maxZoom - 1.0) / (numFocalLengths - 1));
      NSNumber *virtualFocalLength = [NSNumber numberWithFloat:zoomFactor];
      [focalLengths addObject:virtualFocalLength];
      [self.focalLengthToCameraDevice setObject:self.device forKey:virtualFocalLength];
    }
  }

  // Sort focal lengths
  [focalLengths sortUsingComparator:^NSComparisonResult(NSNumber *a, NSNumber *b) {
    return [a compare:b];
  }];

  self.availableFocalLengths = [focalLengths copy];

  // Restore the previous focal length index if available
  // Otherwise, use the first focal length (default behavior)
  if (previousFocalLength != nil) {
    // Try to restore the previous focal length
    NSInteger restoredIndex = [self.availableFocalLengths indexOfObject:previousFocalLength];
    if (restoredIndex != NSNotFound) {
      self.currentFocalLengthIndex = restoredIndex;
      NSLog(@"Restored previous focal length: %@mm (index: %ld)", previousFocalLength, (long)self.currentFocalLengthIndex);
    } else {
      // Previous focal length not found in new list, use first one
      self.currentFocalLengthIndex = 0;
      NSLog(@"Previous focal length %@ not found in new list, using first focal length", previousFocalLength);
    }
  } else {
    // First initialization: use first focal length (default behavior)
    self.currentFocalLengthIndex = 0;
  }

  NSLog(@"Initialized focal lengths: %@", self.availableFocalLengths);
  NSLog(@"Available camera devices: %@", self.availableCameraDevices);

  // If we restored a focal length from rotation, switch to the correct camera device
  if (savedFocalLength > 0 && self.currentFocalLengthIndex >= 0 && self.currentFocalLengthIndex < [self.availableFocalLengths count]) {
    NSNumber *targetFocalLength = [self.availableFocalLengths objectAtIndex:self.currentFocalLengthIndex];
    AVCaptureDevice *targetDevice = [self.focalLengthToCameraDevice objectForKey:targetFocalLength];

    if (targetDevice != nil && ![targetDevice.uniqueID isEqualToString:self.device.uniqueID]) {
      NSLog(@"Switching to camera device for restored focal length %@mm", targetFocalLength);

      // Synchronously switch the camera device since we're already on the session queue
      NSError *error = nil;

      // Begin configuration
      [self.session beginConfiguration];

      // Remove current input
      if (self.videoDeviceInput) {
        [self.session removeInput:self.videoDeviceInput];
      }

      // Create new input for target device
      AVCaptureDeviceInput *newVideoDeviceInput = [AVCaptureDeviceInput deviceInputWithDevice:targetDevice error:&error];

      if (!error && [self.session canAddInput:newVideoDeviceInput]) {
        [self.session addInput:newVideoDeviceInput];
        self.videoDeviceInput = newVideoDeviceInput;
        self.device = targetDevice;

        // Reset zoom to 1.0 for the new device
        if ([targetDevice lockForConfiguration:&error]) {
          targetDevice.videoZoomFactor = 1.0;
          self.videoZoomFactor = 1.0;
          [targetDevice unlockForConfiguration];
        }

        NSLog(@"Successfully switched to camera device for restored focal length: %@", targetDevice.deviceType);
      } else {
        NSLog(@"Failed to switch to camera device for restored focal length: %@", error);
      }

      // Update orientation to preserve current device orientation
      [self updateOrientation:[self getCurrentOrientation]];

      // Commit configuration
      [self.session commitConfiguration];
    }
  }

  // Clear the static saved focal length after restoring
  // This ensures we don't restore the same focal length on subsequent camera switches
  if (savedFocalLength > 0 && hasRestoredFromSavedState) {
    NSLog(@"Clearing saved focal length after restoration");
    savedFocalLength = -1.0f;
    savedFocalLengthIndex = -1;
    hasRestoredFromSavedState = NO;
  }
}

- (NSArray *)getFocusModes {

  NSMutableArray * focusModes = [[NSMutableArray alloc] init];

  if ([self.device isFocusModeSupported:0]) {
    [focusModes addObject:@"fixed"];
  };
  if ([self.device isFocusModeSupported:1]) {
    [focusModes addObject:@"auto"];
  };
  if ([self.device isFocusModeSupported:2]) {
    [focusModes addObject:@"continuous"];
  };

  return (NSArray *) focusModes;
}

- (NSString *) getFocusMode {

  NSString *focusMode;

  AVCaptureDevice * videoDevice = [self cameraWithPosition: self.defaultCamera];
  switch (videoDevice.focusMode) {
    case 0:
        focusMode = @"fixed";
      break;
    case 1:
      focusMode = @"auto";
      break;
    case 2:
      focusMode = @"continuous";
      break;
    default:
      focusMode = @"unsupported";
      NSLog(@"Mode not supported");
  }

  return focusMode;
}

- (NSString *) setFocusMode:(NSString *)focusMode {

  NSString *errMsg;

  AVCaptureDevice * videoDevice = [self cameraWithPosition: self.defaultCamera];
  [self.device lockForConfiguration:nil];

  if ([focusMode isEqual:@"fixed"]) {
    if ([videoDevice isFocusModeSupported:0]) {
      videoDevice.focusMode = 0;
    } else {
      errMsg = @"Focus mode not supported";
    };
  } else if ([focusMode isEqual:@"auto"]) {
    if ([videoDevice isFocusModeSupported:1]) {
      videoDevice.focusMode = 1;
    } else {
      errMsg = @"Focus mode not supported";
    };
  } else if ([focusMode isEqual:@"continuous"]) {
    if ([videoDevice isFocusModeSupported:2]) {
      videoDevice.focusMode = 2;
    } else {
      errMsg = @"Focus mode not supported";
    };
  } else {
    errMsg = @"Exposure mode not supported";
  }

  [self.device unlockForConfiguration];

  if (errMsg) {
    NSLog(@"%@", errMsg);
    return @"ERR01";
  }

  return focusMode;
}

- (NSArray *)getFlashModes {
  NSMutableArray * flashModes = [[NSMutableArray alloc] init];

  if ([self.device hasFlash]) {
    if ([self.device isFlashModeSupported:0]) {
      [flashModes addObject:@"off"];
    };
    if ([self.device isFlashModeSupported:1]) {
      [flashModes addObject:@"on"];
    };
    if ([self.device isFlashModeSupported:2]) {
      [flashModes addObject:@"auto"];
    };
    if ([self.device hasTorch]) {
      [flashModes addObject:@"torch"];
    };
  }

  return (NSArray *) flashModes;
}

- (NSInteger)getFlashMode {

  if ([self.device hasFlash] && [self.device isFlashModeSupported:self.defaultFlashMode]) {
    return self.device.flashMode;
  }

  return -1;
}

- (void)setFlashMode:(NSInteger)flashMode {
  NSError *error = nil;

  // Let's save the setting even if we can't set it up on this camera.
  self.defaultFlashMode = flashMode;

  if ([self.device hasFlash] && [self.device isFlashModeSupported:self.defaultFlashMode]) {

    if ([self.device lockForConfiguration:&error]) {
      if ([self.device hasTorch] && [self.device isTorchAvailable]) {
        self.device.torchMode=0;
      }
      [self.device setFlashMode:self.defaultFlashMode];
      [self.device unlockForConfiguration];

    } else {
        NSLog(@"%@", error);
    }
  } else {
    NSLog(@"Camera has no flash or flash mode not supported");
  }
}

- (BOOL)isTorchActive {
  return ([self.device hasTorch] && [self.device isTorchAvailable] && [self.device isTorchActive]);
}

- (void)setTorchMode {
  NSError *error = nil;

  // Let's save the setting even if we can't set it up on this camera.
  //self.defaultFlashMode = flashMode;

  if ([self.device hasTorch] && [self.device isTorchAvailable]) {

    if ([self.device lockForConfiguration:&error]) {

      if ([self.device isTorchModeSupported:1]) {
        self.device.torchMode=1;
      } else if ([self.device isTorchModeSupported:2]) {
        self.device.torchMode=2;
      } else {
        self.device.torchMode=0;
      }
      [self.device unlockForConfiguration];
    } else {
        NSLog(@"%@", error);
    }
  } else {
    NSLog(@"Camera has no flash or flash mode not supported");
  }
}

- (void)setZoom:(CGFloat)desiredZoomFactor {

  [self.device lockForConfiguration:nil];
  self.videoZoomFactor = MAX(1.0, MIN(desiredZoomFactor, self.device.activeFormat.videoMaxZoomFactor));

  [self.device setVideoZoomFactor:self.videoZoomFactor];
  [self.device unlockForConfiguration];
  NSLog(@"%zd zoom factor set", self.videoZoomFactor);
}

- (CGFloat)getZoom {

  AVCaptureDevice * videoDevice = [self cameraWithPosition: self.defaultCamera];
  return videoDevice.videoZoomFactor;
}

- (float)getHorizontalFOV {

  AVCaptureDevice * videoDevice = [self cameraWithPosition: self.defaultCamera];
  return videoDevice.activeFormat.videoFieldOfView;
}

- (CGFloat)getMaxZoom {

  AVCaptureDevice * videoDevice = [self cameraWithPosition: self.defaultCamera];
  return videoDevice.activeFormat.videoMaxZoomFactor;
}

- (NSArray *)getExposureModes {

  AVCaptureDevice * videoDevice = [self cameraWithPosition: self.defaultCamera];
  NSMutableArray *exposureModes = [[NSMutableArray alloc] init];
  if ([videoDevice isExposureModeSupported:0]) {
    [exposureModes addObject:@"lock"];
  };
  if ([videoDevice isExposureModeSupported:1]) {
    [exposureModes addObject:@"auto"];
  };
  if ([videoDevice isExposureModeSupported:2]) {
    [exposureModes addObject:@"continuous"];
  };
  if ([videoDevice isExposureModeSupported:3]) {
    [exposureModes addObject:@"custom"];
  };
  NSLog(@"%@", exposureModes);
  return (NSArray *) exposureModes;
}

- (NSString *) getExposureMode {

  NSString *exposureMode;

  AVCaptureDevice * videoDevice = [self cameraWithPosition: self.defaultCamera];
  switch (videoDevice.exposureMode) {
    case 0:
        exposureMode = @"lock";
      break;
    case 1:
      exposureMode = @"auto";
      break;
    case 2:
      exposureMode = @"continuous";
      break;
    case 3:
      exposureMode = @"custom";
      break;
    default:
      exposureMode = @"unsupported";
      NSLog(@"Mode not supported");
  }

  return exposureMode;
}

- (NSString *) setExposureMode:(NSString *)exposureMode {

  NSString *errMsg;

  AVCaptureDevice * videoDevice = [self cameraWithPosition: self.defaultCamera];
  [self.device lockForConfiguration:nil];

  if ([exposureMode isEqual:@"lock"]) {
    if ([videoDevice isExposureModeSupported:0]) {
      videoDevice.exposureMode = 0;
    } else {
      errMsg = @"Exposure mode not supported";
    };
  } else if ([exposureMode isEqual:@"auto"]) {
    if ([videoDevice isExposureModeSupported:1]) {
      videoDevice.exposureMode = 1;
    } else {
      errMsg = @"Exposure mode not supported";
    };
  } else if ([exposureMode isEqual:@"continuous"]) {
    if ([videoDevice isExposureModeSupported:2]) {
      videoDevice.exposureMode = 2;
    } else {
      errMsg = @"Exposure mode not supported";
    };
  } else if ([exposureMode isEqual:@"custom"]) {
    if ([videoDevice isExposureModeSupported:3]) {
      videoDevice.exposureMode = 3;
    } else {
      errMsg = @"Exposure mode not supported";
    };
  } else {
    errMsg = @"Exposure mode not supported";
  }

  [self.device unlockForConfiguration];

  if (errMsg) {
    NSLog(@"%@", errMsg);
    return @"ERR01";
  }

  return exposureMode;
}

- (NSArray *)getExposureCompensationRange {

  AVCaptureDevice * videoDevice = [self cameraWithPosition: self.defaultCamera];
  CGFloat maxExposureCompensation = videoDevice.maxExposureTargetBias;
  CGFloat minExposureCompensation = videoDevice.minExposureTargetBias;
  NSArray * exposureCompensationRange = [[NSArray alloc] initWithObjects: [NSNumber numberWithFloat:minExposureCompensation], [NSNumber numberWithFloat:maxExposureCompensation], nil];
  return exposureCompensationRange;
}

- (CGFloat)getExposureCompensation {

  AVCaptureDevice * videoDevice = [self cameraWithPosition: self.defaultCamera];
  NSLog(@"getExposureCompensation: %zd", videoDevice.exposureTargetBias);
  return videoDevice.exposureTargetBias;
}

- (void)setExposureCompensation:(CGFloat)exposureCompensation {
  NSError *error = nil;

  if ([self.device lockForConfiguration:&error]) {
    CGFloat exposureTargetBias = MAX(self.device.minExposureTargetBias, MIN(exposureCompensation, self.device.maxExposureTargetBias));
    [self.device setExposureTargetBias:exposureTargetBias completionHandler:nil];
    [self.device unlockForConfiguration];
  } else {
    NSLog(@"%@", error);
  }

}

- (NSArray *)getSupportedWhiteBalanceModes {

  AVCaptureDevice * videoDevice = [self cameraWithPosition: self.defaultCamera];
  NSLog(@"maxWhiteBalanceGain: %f", videoDevice.maxWhiteBalanceGain);
  NSMutableArray * whiteBalanceModes = [[NSMutableArray alloc] init];
  if ([videoDevice isWhiteBalanceModeSupported:0]) {
    [whiteBalanceModes addObject:@"lock"];
  };
  if ([videoDevice isWhiteBalanceModeSupported:1]) {
    [whiteBalanceModes addObject:@"auto"];
  };
  if ([videoDevice isWhiteBalanceModeSupported:2]) {
    [whiteBalanceModes addObject:@"continuous"];
  };

  NSEnumerator *enumerator = [self.colorTemperatures objectEnumerator];
  TemperatureAndTint * wbTemperature;
  while (wbTemperature =[ enumerator nextObject]) {
    AVCaptureWhiteBalanceTemperatureAndTintValues temperatureAndTintValues;
    temperatureAndTintValues.temperature = (wbTemperature.minTemperature + wbTemperature.maxTemperature) / 2;
    temperatureAndTintValues.tint = wbTemperature.tint;
    AVCaptureWhiteBalanceGains rgbGains = [videoDevice deviceWhiteBalanceGainsForTemperatureAndTintValues:temperatureAndTintValues];
    NSLog(@"mode: %@", wbTemperature.mode);
    NSLog(@"minTemperature: %f", wbTemperature.minTemperature);
    NSLog(@"maxTemperature: %f", wbTemperature.maxTemperature);
    NSLog(@"blueGain: %f", rgbGains.blueGain);
    NSLog(@"redGain: %f", rgbGains.redGain);
    NSLog(@"greenGain: %f", rgbGains.greenGain);
    if ((rgbGains.blueGain >= 1) &&
        (rgbGains.blueGain <= videoDevice.maxWhiteBalanceGain) &&
        (rgbGains.redGain >= 1) &&
        (rgbGains.redGain <= videoDevice.maxWhiteBalanceGain) &&
        (rgbGains.greenGain >= 1) &&
        (rgbGains.greenGain <= videoDevice.maxWhiteBalanceGain)) {
      [whiteBalanceModes addObject:wbTemperature.mode];
    }
  }
  NSLog(@"%@", whiteBalanceModes);
  return (NSArray *) whiteBalanceModes;
}

- (NSString *) getWhiteBalanceMode {

  NSString *whiteBalanceMode;

  AVCaptureDevice * videoDevice = [self cameraWithPosition: self.defaultCamera];
  switch (videoDevice.whiteBalanceMode) {
    case 0:
        whiteBalanceMode = @"lock";
        if (self.currentWhiteBalanceMode != nil) {
          whiteBalanceMode = self.currentWhiteBalanceMode;
        }
      break;
    case 1:
      whiteBalanceMode = @"auto";
      break;
    case 2:
      whiteBalanceMode = @"continuous";
      break;
    default:
      whiteBalanceMode = @"unsupported";
      NSLog(@"White balance mode not supported");
  }

  return whiteBalanceMode;
}

- (NSString *) setWhiteBalanceMode:(NSString *)whiteBalanceMode {

  NSString *errMsg;

  NSLog(@"plugin White balance mode: %@", whiteBalanceMode);
  AVCaptureDevice * videoDevice = [self cameraWithPosition: self.defaultCamera];
  [self.device lockForConfiguration:nil];

  if ([whiteBalanceMode isEqual:@"lock"]) {
    if ([videoDevice isWhiteBalanceModeSupported:0]) {
      videoDevice.whiteBalanceMode = 0;
    } else {
      errMsg = @"White balance mode not supported";
    };
  } else if ([whiteBalanceMode isEqual:@"auto"]) {
    if ([videoDevice isWhiteBalanceModeSupported:1]) {
      videoDevice.whiteBalanceMode = 1;
  } else {
      errMsg = @"White balance mode not supported";
    };
  } else if ([whiteBalanceMode isEqual:@"continuous"]) {
    if ([videoDevice isWhiteBalanceModeSupported:2]) {
      videoDevice.whiteBalanceMode = 2;
    } else {
      errMsg = @"White balance mode not supported";
    };
  } else {
      NSLog(@"Additional modes for %@", whiteBalanceMode);
      TemperatureAndTint * temperatureForWhiteBalanceSetting = [self.colorTemperatures objectForKey:whiteBalanceMode];
      if (temperatureForWhiteBalanceSetting != nil) {
        AVCaptureWhiteBalanceTemperatureAndTintValues temperatureAndTintValues;
        temperatureAndTintValues.temperature = (temperatureForWhiteBalanceSetting.minTemperature + temperatureForWhiteBalanceSetting.maxTemperature) / 2;
        temperatureAndTintValues.tint = temperatureForWhiteBalanceSetting.tint;
        AVCaptureWhiteBalanceGains rgbGains = [videoDevice deviceWhiteBalanceGainsForTemperatureAndTintValues:temperatureAndTintValues];
        if ((rgbGains.blueGain >= 1) &&
           (rgbGains.blueGain <= videoDevice.maxWhiteBalanceGain) &&
           (rgbGains.redGain >= 1) &&
           (rgbGains.redGain <= videoDevice.maxWhiteBalanceGain) &&
           (rgbGains.greenGain >= 1) &&
           (rgbGains.greenGain <= videoDevice.maxWhiteBalanceGain)) {

          [videoDevice setWhiteBalanceModeLockedWithDeviceWhiteBalanceGains:rgbGains completionHandler:nil];
          self.currentWhiteBalanceMode = whiteBalanceMode;
        } else {
          errMsg = @"White balance mode not supported";
        }
      } else {
      errMsg = @"White balance mode not supported";
    }
  }
  [self.device unlockForConfiguration];

  if (errMsg) {
    NSLog(@"%@", errMsg);
    return @"ERR01";
  }

  return whiteBalanceMode;
}

- (void)observeValueForKeyPath:(NSString *)keyPath ofObject:(id)object change:(NSDictionary *)change context:(void *)context {
    // removes the observer, when the camera is done focussing.
    if( [keyPath isEqualToString:@"adjustingFocus"] ){
        BOOL adjustingFocus = [[change objectForKey:NSKeyValueChangeNewKey] isEqualToNumber:[NSNumber numberWithInt:1]];
        if(!adjustingFocus){
            [self.device removeObserver:self forKeyPath:@"adjustingFocus"];
            [self.delegate onFocus];
        }
    }
}

- (void) takePictureOnFocus{
    // add an observer, when takePictureOnFocus is requested.
    int flag = NSKeyValueObservingOptionNew;
    [self.device addObserver:self forKeyPath:@"adjustingFocus" options:flag context:nil];
}

- (void) tapToFocus:(CGFloat)xPoint yPoint:(CGFloat)yPoint {

  [self.device lockForConfiguration:nil];

  CGRect screenRect = [[UIScreen mainScreen] bounds];
  CGFloat screenWidth = screenRect.size.width;
  CGFloat screenHeight = screenRect.size.height;
  CGFloat focus_x = xPoint/screenWidth;
  CGFloat focus_y = yPoint/screenHeight;

  if ([self.device isFocusModeSupported:AVCaptureFocusModeAutoFocus]){
    [self.device setFocusPointOfInterest:CGPointMake(focus_x,focus_y)];
    [self.device setFocusMode:AVCaptureFocusModeAutoFocus];
  }
  if ([self.device isExposureModeSupported:AVCaptureExposureModeAutoExpose]){
    [self.device setExposurePointOfInterest:CGPointMake(focus_x,focus_y)];
    [self.device setExposureMode:AVCaptureExposureModeAutoExpose];
  }

  [self.device unlockForConfiguration];
}

- (void)checkDeviceAuthorizationStatus {
  NSString *mediaType = AVMediaTypeVideo;

  [AVCaptureDevice requestAccessForMediaType:mediaType completionHandler:^(BOOL granted) {
    if (!granted) {
      //Not granted access to mediaType
      dispatch_async(dispatch_get_main_queue(), ^{
          [[[UIAlertView alloc] initWithTitle:@"Error"
                                      message:@"Camera permission not found. Please, check your privacy settings."
                                     delegate:self
                            cancelButtonTitle:@"OK"
                            otherButtonTitles:nil] show];
      });
    }
  }];
}

// Find a camera with the specified AVCaptureDevicePosition, returning nil if one is not found
- (AVCaptureDevice *) cameraWithPosition:(AVCaptureDevicePosition) position {
  NSArray *devices = [AVCaptureDevice devicesWithMediaType:AVMediaTypeVideo];
  for (AVCaptureDevice *device in devices){
    if ([device position] == position)
      return device;
  }
  return nil;
}

// Switch to the camera device for the specified focal length
- (void) switchToCameraDeviceForFocalLength:(NSNumber *)focalLength completion:(void(^)(BOOL switched))completion {
  dispatch_async(self.sessionQueue, ^{
    NSError *error = nil;
    BOOL success = TRUE;

    // Get the target camera device for this focal length
    AVCaptureDevice *targetDevice = [self.focalLengthToCameraDevice objectForKey:focalLength];

    if (targetDevice == nil) {
      NSLog(@"No camera device found for focal length: %@", focalLength);
      completion(FALSE);
      return;
    }

    // If it's the same device, no need to switch
    if ([targetDevice.uniqueID isEqualToString:self.device.uniqueID]) {
      NSLog(@"Already on camera device for focal length: %@", focalLength);
      completion(TRUE);
      return;
    }

    NSLog(@"Switching camera device from %@ to %@ for focal length %@",
          self.device.deviceType, targetDevice.deviceType, focalLength);

    // Begin configuration
    [self.session beginConfiguration];

    // Remove current input
    if (self.videoDeviceInput) {
      [self.session removeInput:self.videoDeviceInput];
    }

    // Create new input for target device
    AVCaptureDeviceInput *newVideoDeviceInput = [AVCaptureDeviceInput deviceInputWithDevice:targetDevice error:&error];

    if (error) {
      NSLog(@"Failed to create device input for focal length switch: %@", error);
      [self.session commitConfiguration];
      completion(FALSE);
      return;
    }

    // Add new input
    if ([self.session canAddInput:newVideoDeviceInput]) {
      [self.session addInput:newVideoDeviceInput];
      self.videoDeviceInput = newVideoDeviceInput;
      self.device = targetDevice;

      // Reset zoom to 1.0 for the new device
      if ([targetDevice lockForConfiguration:&error]) {
        targetDevice.videoZoomFactor = 1.0;
        self.videoZoomFactor = 1.0;
        [targetDevice unlockForConfiguration];
      }

      NSLog(@"Successfully switched to camera device: %@", targetDevice.deviceType);
    } else {
      NSLog(@"Cannot add new video device input");
      success = FALSE;
    }

    // Update orientation to preserve current device orientation
    [self updateOrientation:[self getCurrentOrientation]];

    // Commit configuration
    [self.session commitConfiguration];

    // Add a small delay to allow the session to stabilize with the new device
    // This prevents "taking picture does nothing" issue after focal length switch
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(500 * NSEC_PER_MSEC)), self.sessionQueue, ^{
      NSLog(@"Camera device stabilized after focal length switch");
      completion(success);
    });
  });
}

@end
