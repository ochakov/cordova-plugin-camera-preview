#import "CameraRenderController.h"
#import <CoreVideo/CVOpenGLESTextureCache.h>
#import <GLKit/GLKit.h>
#import <OpenGLES/ES2/glext.h>

@implementation CameraRenderController
@synthesize context = _context;
@synthesize delegate;


- (CameraRenderController *)init {
  if (self = [super init]) {
    self.renderLock = [[NSLock alloc] init];
  }
  return self;
}

- (void)loadView {
  GLKView *glkView = [[GLKView alloc] init];
  [glkView setBackgroundColor:[UIColor blackColor]];
  [self setView:glkView];
}

- (void)viewDidLoad {
  [super viewDidLoad];

  self.context = [[EAGLContext alloc] initWithAPI:kEAGLRenderingAPIOpenGLES2];

  if (!self.context) {
    NSLog(@"Failed to create ES context");
  }

  CVReturn err = CVOpenGLESTextureCacheCreate(kCFAllocatorDefault, NULL, self.context, NULL, &_videoTextureCache);
  if (err) {
    NSLog(@"Error at CVOpenGLESTextureCacheCreate %d", err);
    return;
  }

  GLKView *view = (GLKView *)self.view;
  view.context = self.context;
  view.drawableDepthFormat = GLKViewDrawableDepthFormat24;
  view.contentMode = UIViewContentModeScaleToFill;

  glGenRenderbuffers(1, &_renderBuffer);
  glBindRenderbuffer(GL_RENDERBUFFER, _renderBuffer);

  self.ciContext = [CIContext contextWithEAGLContext:self.context];

  if (self.dragEnabled) {
    //add drag action listener
    NSLog(@"Enabling view dragging");
    UIPanGestureRecognizer *drag = [[UIPanGestureRecognizer alloc] initWithTarget:self action:@selector(handlePan:)];
    [self.view addGestureRecognizer:drag];
  }

  if (self.tapToFocus && self.tapToTakePicture){
    //tap to focus and take picture
    UITapGestureRecognizer *tapToFocusAndTakePicture = [[UITapGestureRecognizer alloc] initWithTarget:self action:@selector (handleFocusAndTakePictureTap:)];
    [self.view addGestureRecognizer:tapToFocusAndTakePicture];

  } else if (self.tapToFocus){
    // tap to focus
    UITapGestureRecognizer *tapToFocusGesture = [[UITapGestureRecognizer alloc] initWithTarget:self action:@selector (handleFocusTap:)];
    [self.view addGestureRecognizer:tapToFocusGesture];

  } else if (self.tapToTakePicture) {
    //tap to take picture
    UITapGestureRecognizer *takePictureTap = [[UITapGestureRecognizer alloc] initWithTarget:self action:@selector(handleTakePictureTap:)];
    [self.view addGestureRecognizer:takePictureTap];
  }

  self.view.userInteractionEnabled = self.dragEnabled || self.tapToTakePicture || self.tapToFocus;
}

- (void) viewWillAppear:(BOOL)animated {
  [super viewWillAppear:animated];

  // Backup for rotations that do not reach viewWillTransitionToSize (removed in viewWillDisappear).
  [[NSNotificationCenter defaultCenter] addObserver:self
                                           selector:@selector(orientationDidChange:)
                                               name:UIDeviceOrientationDidChangeNotification
                                             object:nil];
  [self refreshOrientation];

  [[NSNotificationCenter defaultCenter] addObserver:self
                                           selector:@selector(appplicationIsActive:)
                                               name:UIApplicationDidBecomeActiveNotification
                                             object:nil];

  [[NSNotificationCenter defaultCenter] addObserver:self
                                           selector:@selector(applicationEnteredForeground:)
                                               name:UIApplicationWillEnterForegroundNotification
                                             object:nil];

  dispatch_async(self.sessionManager.sessionQueue, ^{
      // setupSession starts the session once it is configured. Starting it here before that would
      // run it empty and make every input and output added afterwards restart it. This restarts
      // it when the view appears again after it was stopped.
      if (self.sessionManager.sessionConfigured) {
        NSLog(@"Starting session");
        [self.sessionManager.session startRunning];
      }
      });
}

- (void) viewWillDisappear:(BOOL)animated {
  [super viewWillDisappear:animated];

  // Save focal length state before view disappears (e.g., during rotation)
  [self.sessionManager saveFocalLengthState];

  [[NSNotificationCenter defaultCenter] removeObserver:self
                                                  name:UIApplicationDidBecomeActiveNotification
                                                object:nil];

  [[NSNotificationCenter defaultCenter] removeObserver:self
                                                  name:UIApplicationWillEnterForegroundNotification
                                                object:nil];

  [[NSNotificationCenter defaultCenter] removeObserver:self
                                                  name:UIDeviceOrientationDidChangeNotification
                                                object:nil];

  dispatch_async(self.sessionManager.sessionQueue, ^{
      NSLog(@"Stopping session");
      [self.sessionManager.session stopRunning];
      });
}

- (void) handleFocusAndTakePictureTap:(UITapGestureRecognizer*)recognizer {
  NSLog(@"handleFocusAndTakePictureTap");

  // let the delegate take an image, the next time the image is in focus.
  [self.delegate invokeTakePictureOnFocus];

  // let the delegate focus on the tapped point.
  [self handleFocusTap:recognizer];
}

- (void) handleTakePictureTap:(UITapGestureRecognizer*)recognizer {
  NSLog(@"handleTakePictureTap");
  [self.delegate invokeTakePicture];
}

- (void) handleFocusTap:(UITapGestureRecognizer*)recognizer {
  NSLog(@"handleTapFocusTap");

  if (recognizer.state == UIGestureRecognizerStateEnded)    {
    CGPoint point = [recognizer locationInView:self.view];
    [self.delegate invokeTapToFocus:point];
  }
}

- (void) onFocus{
  [self.delegate invokeTakePicture];
}

- (IBAction)handlePan:(UIPanGestureRecognizer *)recognizer {
        CGPoint translation = [recognizer translationInView:self.view];
        recognizer.view.center = CGPointMake(recognizer.view.center.x + translation.x,
                                             recognizer.view.center.y + translation.y);
        [recognizer setTranslation:CGPointMake(0, 0) inView:self.view];
}

- (void) appplicationIsActive:(NSNotification *)notification {
  dispatch_async(self.sessionManager.sessionQueue, ^{
      if (self.sessionManager.sessionConfigured) {
        NSLog(@"Starting session");
        [self.sessionManager.session startRunning];
      }
      });
}

- (void) applicationEnteredForeground:(NSNotification *)notification {
  dispatch_async(self.sessionManager.sessionQueue, ^{
      NSLog(@"Stopping session");
      [self.sessionManager.session stopRunning];
      });
}

-(void)captureOutput:(AVCaptureOutput *)captureOutput didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer fromConnection:(AVCaptureConnection *)connection {
  if ([self.renderLock tryLock]) {
    CVPixelBufferRef pixelBuffer = (CVPixelBufferRef)CMSampleBufferGetImageBuffer(sampleBuffer);
    CIImage *image = [CIImage imageWithCVPixelBuffer:pixelBuffer];


    CGFloat scaleHeight = self.view.frame.size.height/image.extent.size.height;
    CGFloat scaleWidth = self.view.frame.size.width/image.extent.size.width;

    CGFloat scale, x, y;
    if (scaleHeight < scaleWidth) {
      scale = scaleWidth;
      x = 0;
      y = ((scale * image.extent.size.height) - self.view.frame.size.height ) / 2;
    } else {
      scale = scaleHeight;
      x = ((scale * image.extent.size.width) - self.view.frame.size.width )/ 2;
      y = 0;
    }

    // scale - translate
    CGAffineTransform xscale = CGAffineTransformMakeScale(scale, scale);
    CGAffineTransform xlate = CGAffineTransformMakeTranslation(-x, -y);
    CGAffineTransform xform =  CGAffineTransformConcat(xscale, xlate);

    CIFilter *centerFilter = [CIFilter filterWithName:@"CIAffineTransform"  keysAndValues:
      kCIInputImageKey, image,
      kCIInputTransformKey, [NSValue valueWithBytes:&xform objCType:@encode(CGAffineTransform)],
      nil];

    CIImage *transformedImage = [centerFilter outputImage];

    // crop
    CIFilter *cropFilter = [CIFilter filterWithName:@"CICrop"];
    CIVector *cropRect = [CIVector vectorWithX:0 Y:0 Z:self.view.frame.size.width W:self.view.frame.size.height];
    [cropFilter setValue:transformedImage forKey:kCIInputImageKey];
    [cropFilter setValue:cropRect forKey:@"inputRectangle"];
    CIImage *croppedImage = [cropFilter outputImage];

    //fix front mirroring
    if (self.sessionManager.defaultCamera == AVCaptureDevicePositionFront) {
      CGAffineTransform matrix = CGAffineTransformTranslate(CGAffineTransformMakeScale(-1, 1), 0, croppedImage.extent.size.height);
      croppedImage = [croppedImage imageByApplyingTransform:matrix];
    }

    self.latestFrame = croppedImage;

    CGFloat pointScale;
    if ([[UIScreen mainScreen] respondsToSelector:@selector(nativeScale)]) {
      pointScale = [[UIScreen mainScreen] nativeScale];
    } else {
      pointScale = [[UIScreen mainScreen] scale];
    }
    CGRect dest = CGRectMake(0, 0, self.view.frame.size.width*pointScale, self.view.frame.size.height*pointScale);

    [self.ciContext drawImage:croppedImage inRect:dest fromRect:[croppedImage extent]];
    [self.context presentRenderbuffer:GL_RENDERBUFFER];
    [(GLKView *)(self.view)display];
    [self.renderLock unlock];
  }
}

- (void)viewDidUnload {
  [super viewDidUnload];

  if ([EAGLContext currentContext] == self.context) {
    [EAGLContext setCurrentContext:nil];
  }
  self.context = nil;
}

- (void)didReceiveMemoryWarning {
  [super didReceiveMemoryWarning];
  // Release any cached data, images, etc. that aren't in use.
}

- (BOOL)shouldAutorotate {
  return YES;
}

// Replaces the deprecated willRotateToInterfaceOrientation:duration:, which no longer reliably
// reaches a child view controller: the preview kept its portrait capture orientation in landscape.
- (void)viewWillTransitionToSize:(CGSize)size withTransitionCoordinator:(id<UIViewControllerTransitionCoordinator>)coordinator {
  [super viewWillTransitionToSize:size withTransitionCoordinator:coordinator];
  [coordinator animateAlongsideTransition:^(id<UIViewControllerTransitionCoordinatorContext> context) {
    [self updateFullscreenFrameToSize:size];
    [self refreshOrientation];
  } completion:^(id<UIViewControllerTransitionCoordinatorContext> context) {
    [self updateFullscreenFrameToSize:[[UIScreen mainScreen] bounds].size];
    [self refreshOrientation];
  }];
}

- (void)viewDidLayoutSubviews {
  [super viewDidLayoutSubviews];
  [self refreshOrientation];
}

- (void)orientationDidChange:(NSNotification *)notification {
  [self updateFullscreenFrameToSize:[[UIScreen mainScreen] bounds].size];
  [self refreshOrientation];
}

// A fullscreen preview (at 0,0 and screen-sized in either orientation) follows the screen size.
- (void)updateFullscreenFrameToSize:(CGSize)size {
  CGRect currentFrame = self.view.frame;
  if (currentFrame.origin.x != 0 || currentFrame.origin.y != 0) {
    return;
  }
  // Only a preview that covers the screen in the other orientation is resized.
  if (size.width != size.height && currentFrame.size.width == size.height && currentFrame.size.height == size.width) {
    self.view.frame = CGRectMake(0, 0, size.width, size.height);
    NSLog(@"Updated fullscreen preview frame to: %f x %f", size.width, size.height);
  }
}

// Points the capture connections at the current interface orientation when it changed, so that the
// preview frames and pictures are upright in landscape too. Main thread only.
- (void)refreshOrientation {
  if (self.sessionManager == nil) {
    return;
  }
  UIInterfaceOrientation orientation = [CameraSessionManager interfaceOrientationForView:self.view];
  if (orientation == UIInterfaceOrientationUnknown || orientation == self.sessionManager.interfaceOrientation) {
    return;
  }
  NSLog(@"Interface orientation changed to %ld", (long)orientation);
  self.sessionManager.interfaceOrientation = orientation;
  AVCaptureVideoOrientation videoOrientation = [self.sessionManager getCurrentOrientation:orientation];
  CameraSessionManager *sessionManager = self.sessionManager;
  dispatch_async(sessionManager.sessionQueue, ^{
    [sessionManager updateOrientation:videoOrientation];
  });
}

@end
