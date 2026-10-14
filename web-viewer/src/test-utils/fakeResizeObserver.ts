/** jsdom has no ResizeObserver; this fake lets a test decide when and how large an element "is". */
export class FakeResizeObserver {
  static instances: FakeResizeObserver[] = []

  private readonly callback: ResizeObserverCallback
  private targets: Element[] = []

  constructor(callback: ResizeObserverCallback) {
    this.callback = callback
    FakeResizeObserver.instances.push(this)
  }

  observe(target: Element): void {
    this.targets.push(target)
  }

  unobserve(target: Element): void {
    this.targets = this.targets.filter(item => item !== target)
  }

  disconnect(): void {
    this.targets = []
    FakeResizeObserver.instances = FakeResizeObserver.instances.filter(item => item !== this)
  }

  private fire(width: number, height: number): void {
    const entries = this.targets.map(
      target => ({ target, contentRect: { width, height } }) as unknown as ResizeObserverEntry
    )
    this.callback(entries, this as unknown as ResizeObserver)
  }

  /** Reports the given size for every element currently observed. */
  static resizeAll(width: number, height: number): void {
    FakeResizeObserver.instances.forEach(observer => observer.fire(width, height))
  }
}
