package com.missmess.coverflowview;

import android.content.Context;
import android.content.res.TypedArray;
import android.database.DataSetObserver;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.widget.RelativeLayout;

import com.nineoldandroids.animation.Animator;
import com.nineoldandroids.animation.AnimatorListenerAdapter;
import com.nineoldandroids.animation.ValueAnimator;
import com.nineoldandroids.view.ViewHelper;

import java.util.Locale;

/**
 * 将一组view以CoverFlow效果展示出来。
 *
 * <p>原理：假设一屏可显示的view数目为totalVis = 2 * vis + 1（始终为奇数）。左右两边最多显示vis个
 * view。adapter的个数为count;
 * <ol>
 *     <li>adapter中的item将按顺序排列在CoverFlowView中。初始显示的一组view为adapter中position为
 *     0~totalVis的item，并且最中间的view设index = 0，即index=0对应position=vis。
 *     参考 {@link #getActuallyPosition(int)}</li>
 *     <li>index从左到右增大。在loop模式下，index没有取值范围。在非loop模式，index取值范围
 *     为[-vis, count-vis-1]</li>
 *     <li>内部使用{@link #showViewArray}存储正显示在CoverFlowView上的item。这个map以
 *     adapter position作为key存储view</li>
 *     <li>CoverFlowView通过{@link #addView(View, int)}的index order来控制CoverFlow的层叠效果。
 *     最中间的view的index order最大，越往两边index order越小。</li>
 *     <li>CoverFlowView发生任何移动时{@link #mOffset}都会改变，代表偏移量（浮点数）。滑动停止时，
 *     {@link #mOffset}=index</li>
 *     <li>每当滑动到两个view之间的一半(offset值为0.5)时，将会移除一个边界view（往左滑移除右边界）和获取一个新的
 *     显示view，并且调整view的index order以保持正确的层叠效果，参见{@link #onLayout(boolean, int, int, int, int)}</li>
 * </ol>
 * </p>
 *
 */
public class CoverFlowView extends RelativeLayout {

    public enum CoverFlowGravity {
        TOP, BOTTOM, CENTER_VERTICAL
    }

    public enum CoverFlowLayoutMode {
        MATCH_PARENT, WRAP_CONTENT
    }

    /**
     * The CoverFlowView is not currently scrolling.
     *
     * @see #getScrollState()
     */
    public static final int SCROLL_STATE_IDLE = 0;

    /**
     * The CoverFlowView is currently being dragged by outside input such as user touch input.
     *
     * @see #getScrollState()
     */
    public static final int SCROLL_STATE_DRAGGING = 1;

    /**
     * The CoverFlowView is currently animating to a final position while not under
     * outside control.
     *
     * @see #getScrollState()
     */
    public static final int SCROLL_STATE_SETTLING = 2;

    private int mScrollState = SCROLL_STATE_IDLE;

    protected CoverFlowGravity mGravity;
    protected CoverFlowLayoutMode mLayoutMode;

    /**
     * 显示在CoverFlowView上的所有view的map，其中key为对应的adapter position
     */
    private SparseArray<View> showViewArray;
    // 一屏显示的数量
    private int mVisibleChildCount;
    // 左右两边显示的个数
    protected int mSiblingCount = 1;
    private float mOffset = 0f;

    private int paddingLeft;
    private int paddingRight;
    private int paddingTop;
    private int paddingBottom;
    private float reflectHeightFraction = 0;
    private int reflectGap = 0;
    private int mWidth; // 控件的宽度
    private int mChildHeight; // child的高度
    private int mChildTranslateY;

    // 基础alphaֵ
    private static final int ALPHA_DATUM = 76;
    // 临界view alpha
    private int STANDARD_ALPHA;

    // 临界view的scale比例，因为是反向取值，所以这个值越大，临界view会越小
    private float mEdgeScale;
    private static final float MIN_SCALE = 0f;
    private static final float MAX_SCALE = 0.8f;
    private static final float DEFAULT_SCALE = 0.25f;

    private static float MOVE_POS_MULTIPLE = 3.0f;
    private static final float MOVE_SPEED_MULTIPLE = 1;
    private static final float MAX_SPEED = 6.0f;
    private static final float FRICTION = 10.0f;
    private static int mTouchSlop;

    private ACoverFlowAdapter mAdapter;
    private AdapterDataSetObserver mDataSetObserver;
    private OnTopViewClickListener mTopViewClickLister;
    private OnViewOnTopListener mViewOnTopListener;
    private OnTopViewLongClickListener mTopViewLongClickLister;

    private boolean mDataChanged = false;
    private Float mPostOffset;

    private boolean mLoopMode;
    int lastMidIndex = -1; //最近的中间view的offset值
    int mViewOnTopPosition = -1; // view处于顶部选中状态的的position，-1代表无

    private View touchViewItem = null;
    private boolean isOnTopView = false;

    private Runnable longClickRunnable = null;

    private boolean mTouchCanceled = false;
    private float mTouchStartPos;
    private float mTouchStartX;
    private float mTouchStartY;

    private long mStartTime;
    private float mStartOffset;
    private float mStartSpeed;
    private float mDuration;

    private Runnable mSettleAnimationRunnable;
    private boolean clickSwitchEnable = true;
    private ValueAnimator mScrollAnimator;
    private VelocityTracker mVelocity;

    public CoverFlowView(Context context) {
        super(context);
        init();
    }

    public CoverFlowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initAttributes(context, attrs);
        init();
    }

    public CoverFlowView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initAttributes(context, attrs);
        init();
    }

    private void initAttributes(Context context, AttributeSet attrs) {
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.CoverFlowView);

        int totalVisibleChildren = a.getInt(
                R.styleable.CoverFlowView_visibleViews, 3);
        if (totalVisibleChildren % 2 == 0) { // 一屏幕必须是奇数显示
            throw new IllegalArgumentException(
                    "visible views must be an odd number");
        }
        if (totalVisibleChildren < 3) {
            throw new IllegalArgumentException(
                    "visible views must be a number greater than 3");
        }

        mSiblingCount = totalVisibleChildren >> 1; // 计算出左右两两边的显示个数
        mVisibleChildCount = totalVisibleChildren;

        mLoopMode = a.getBoolean(R.styleable.CoverFlowView_loopMode, true);

        float scaleRatio = a.getFraction(R.styleable.CoverFlowView_scaleRatio, 1, 1, -1f);
        if (scaleRatio == -1) {
            mEdgeScale = DEFAULT_SCALE;
        } else {
            mEdgeScale = (MAX_SCALE - MIN_SCALE) * scaleRatio + MIN_SCALE;
        }

        mGravity = CoverFlowGravity.values()[a.getInt(
                R.styleable.CoverFlowView_coverflowGravity,
                CoverFlowGravity.CENTER_VERTICAL.ordinal())];

        mLayoutMode = CoverFlowLayoutMode.values()[a.getInt(
                R.styleable.CoverFlowView_coverflowLayoutMode,
                CoverFlowLayoutMode.WRAP_CONTENT.ordinal())];

        a.recycle();
    }

    private void init() {
        setWillNotDraw(true);
        setClickable(true);

        final ViewConfiguration vc = ViewConfiguration.get(getContext());
        mTouchSlop = vc.getScaledTouchSlop();

        if (showViewArray == null) {
            showViewArray = new SparseArray<>();
        }

        // 计算透明度
        STANDARD_ALPHA = (255 - ALPHA_DATUM) / mSiblingCount;

        if (mGravity == null) {
            mGravity = CoverFlowGravity.CENTER_VERTICAL;
        }
        if (mLayoutMode == null) {
            mLayoutMode = CoverFlowLayoutMode.WRAP_CONTENT;
        }

        resetPosition();
    }

    private void resetPosition() {
        mOffset = 0f;
        lastMidIndex = 0;
        mStartOffset = 0;
        mViewOnTopPosition = -1;
    }

    /**
     * 创建children view，添加到空间中，并缓存在集合中。
     * @param inLayout 是否在onLayout中调用
     * @param midAdapterPosition 中间的view对应的adapter position
     */
    private void applyLayoutChildren(boolean inLayout, int midAdapterPosition) {
//        Log.v("CoverFlowView", "applyLayoutChildren:mid position=" + midAdapterPosition);
        if (inLayout) {
            removeAllViewsInLayout();
        } else {
            removeAllViews();
        }
        ACoverFlowAdapter adapter = mAdapter;

        if (adapter != null) {
            int count = adapter.getCount();
            if (count == 0) {
                return;
            }

            if (count < mVisibleChildCount) {
                throw new IllegalArgumentException("adapter's count must be greater than visible views number");
            }

            SparseArray<View> temp = new SparseArray<>();
            for (int i = 0, j = (midAdapterPosition - mSiblingCount); i < mVisibleChildCount && i < count; ++i, ++j) {
                View convertView;
                int index = -1;
                View view;
                if (j < 0) {
                    if (mLoopMode) {
                        index = count + j;

                    }
                } else if (j >= count) {
                    if (mLoopMode) {
                        index = j - count;
                    }
                } else {
                    index = j;
                }

                if (index != -1) { //需要获取view
                    convertView = showViewArray.get(index);
                    view = mAdapter.getView(index, convertView, this);
                    temp.put(index, view);

                    //按Z轴顺序添加view，保持层叠效果
                    int pos;
                    if (i <= mSiblingCount) {
                        pos = -1;
                    } else {
                        pos = 0;
                    }
                    if (inLayout) {
                        LayoutParams params = (LayoutParams) view.getLayoutParams();
                        if (params == null) {
                            params = (LayoutParams) generateDefaultLayoutParams();
                        }
                        addViewInLayout(view, pos, params);
                    } else {
                        addView(view, pos);
                    }
                }
            }

            showViewArray.clear();
            showViewArray = temp;

            if (inLayout) {
                // 如果在layout进程中，不调用此句不会刷新界面？
                // 不太清楚原因
                post(new Runnable() {
                    @Override
                    public void run() {
                        requestLayout();
                    }
                });
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
//        Log.v("CoverFlowView", "onMeasure");
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        if (mAdapter == null || showViewArray.size() <= 0) {
            return;
        }

        paddingLeft = getPaddingLeft();
        paddingRight = getPaddingRight();
        paddingTop = getPaddingTop();
        paddingBottom = getPaddingBottom();

        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        // 控件高度
        int avaiblableHeight = heightSize - paddingTop - paddingBottom;

        int maxChildTotalHeight = 0;
        for (int i = 0; i < getChildCount() && i < mVisibleChildCount && i < showViewArray.size(); ++i) {

            //			View view = showViewArray.get(i+firstIndex);
            View view = getChildAt(i);
            measureChild(view, widthMeasureSpec, heightMeasureSpec);

            //			final int childHeight = ScreenUtil.dp2px(getContext(), 110);
            final int childHeight = view.getMeasuredHeight();
            final int childTotalHeight = (int) (childHeight + childHeight
                    * reflectHeightFraction + reflectGap);

            // 孩子的最大高度
            maxChildTotalHeight = (maxChildTotalHeight < childTotalHeight) ? childTotalHeight
                    : maxChildTotalHeight;
        }

        // 如果控件模式为确切值 或者 最大是
        if (heightMode == MeasureSpec.EXACTLY
                || heightMode == MeasureSpec.AT_MOST) {
            // if height which parent provided is less than child need, scale
            // child height to parent provide
            // 如果控件高度小于孩子控件高度 则缩放孩子高度为控件高度
            if (avaiblableHeight < maxChildTotalHeight) {
                mChildHeight = avaiblableHeight;
            } else {
                // if larger than, depends on layout mode
                // if layout mode is match_parent, scale child height to parent
                // provide
                // 如果是填充父窗体模式 则将孩子的高度 设为控件高度
                if (mLayoutMode == CoverFlowLayoutMode.MATCH_PARENT) {
                    mChildHeight = avaiblableHeight;
                    // if layout mode is wrap_content, keep child's original
                    // height
                    // 如果是包裹内容 则将孩子的高度设为孩子允许的最大高度
                } else if (mLayoutMode == CoverFlowLayoutMode.WRAP_CONTENT) {
                    mChildHeight = maxChildTotalHeight;

                    // adjust parent's height
                    // 计算出控件的高度
                    if (heightMode == MeasureSpec.AT_MOST) {
                        heightSize = mChildHeight + paddingTop + paddingBottom;
                    }
                }
            }
        } else {
            // height mode is unspecified
            // 如果空间高度 没有明确定义
            // 如果孩子的模式为填充父窗体
            if (mLayoutMode == CoverFlowLayoutMode.MATCH_PARENT) {
                mChildHeight = avaiblableHeight;
                // 如果孩子的模式为包裹内容
            } else if (mLayoutMode == CoverFlowLayoutMode.WRAP_CONTENT) {
                mChildHeight = maxChildTotalHeight;

                // 计算出控件的高度
                // adjust parent's height
                heightSize = mChildHeight + paddingTop + paddingBottom;
            }
        }

        // Adjust movement in y-axis according to gravity
        // 计算出孩子的原点 Y坐标
        if (mGravity == CoverFlowGravity.CENTER_VERTICAL) {// 竖直居中
            mChildTranslateY = (heightSize >> 1) - (mChildHeight >> 1);
        } else if (mGravity == CoverFlowGravity.TOP) {// 顶部对齐
            mChildTranslateY = paddingTop;
        } else if (mGravity == CoverFlowGravity.BOTTOM) {// 底部对齐
            mChildTranslateY = heightSize - paddingBottom - mChildHeight;
        }

        //		mReflectionTranslateY = (int) (mChildTranslateY + mChildHeight - mChildHeight
        //				* reflectHeightFraction);

        setMeasuredDimension(widthSize, heightSize);
        mWidth = widthSize;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
//        Log.v("CoverFlowView", "onLayout");
        ACoverFlowAdapter adapter = mAdapter;
        float offset;
        if (mDataChanged && mPostOffset != null) {
            offset = mPostOffset;
            mPostOffset = null;
        } else {
            offset = mOffset;
        }
        int mid = (int) Math.floor(offset + 0.5);
        //右边孩子的数量
        int rightCount = mSiblingCount;
        //左边孩子的数量
        int leftCount = mSiblingCount;

        if (mDataChanged) {
            applyLayoutChildren(true, getActuallyPosition(mid));
            mDataChanged = false;
        } else {
            if (lastMidIndex + 1 == mid) { //右滑至item出现了
                int actuallyPositionStart = getActuallyPosition(lastMidIndex - leftCount);
                View view = showViewArray.get(actuallyPositionStart);
                showViewArray.remove(actuallyPositionStart);
                removeViewInLayout(view);

                // 非loop模式下，index<=count-vis-1。所以mid<=count-vis-1-vis
                boolean avail = mid <= (adapter.getCount() - mSiblingCount - 1) - mSiblingCount;
                if (mLoopMode || avail) {
                    int actuallyPositionEnd = getActuallyPosition(mid + rightCount);
                    View viewItem = adapter.getView(actuallyPositionEnd, view, this);
                    showViewArray.put(actuallyPositionEnd, viewItem);
                    LayoutParams params = (LayoutParams) viewItem.getLayoutParams();
                    if (params == null) {
                        params = (LayoutParams) generateDefaultLayoutParams();
                    }
                    addViewInLayout(viewItem, 0, params);
                }

                int actuallyPositionMid = getActuallyPosition(mid);
                View midView = showViewArray.get(actuallyPositionMid);
                midView.bringToFront();
            } else if (lastMidIndex - 1 == mid) { //左滑至item出现了
                int actuallyPositionEnd = getActuallyPosition(lastMidIndex + rightCount);
                View view = showViewArray.get(actuallyPositionEnd);
                showViewArray.remove(actuallyPositionEnd);
                removeViewInLayout(view);

                // 非loop模式下，index>=-vis。所以mid>=-vis+vis
                boolean avail = mid >= 0;
                if (mLoopMode || avail) {
                    int actuallyPositionStart = getActuallyPosition(mid - leftCount);
                    View viewItem = adapter.getView(actuallyPositionStart, view, this);
                    showViewArray.put(actuallyPositionStart, viewItem);
                    LayoutParams params = (LayoutParams) viewItem.getLayoutParams();
                    if (params == null) {
                        params = (LayoutParams) generateDefaultLayoutParams();
                    }
                    addViewInLayout(viewItem, 0, params);
                }

                int actuallyPositionMid = getActuallyPosition(mid);
                View midView = showViewArray.get(actuallyPositionMid);
                midView.bringToFront();
            }
        }

        lastMidIndex = mid;

        int i;
        // draw the left children
        // 计算左边孩子的位置
        int startPos = mid - leftCount;
        for (i = startPos; i < mid; ++i) {
            layoutLeftChild(i, i - offset);
        }

        // 计算 右边 和 中间
        int endPos = mid + rightCount;
        for (i = endPos; i >= mid; i--) {
            layoutRightChild(i, i - offset);
        }
         // on top
        if ((offset - (int) offset) == 0.0f) {
            int top = getActuallyPosition((int) offset);
            if (top != mViewOnTopPosition) {
                mViewOnTopPosition = top;
                if (mViewOnTopListener != null)
                    mViewOnTopListener.viewOnTop(top, getTopView());
            }
        }
    }

    private View layoutLeftChild(int position, float offset) {
        //获取实际的position
        int actuallyPosition = getActuallyPosition(position);
        View child = showViewArray.get(actuallyPosition);
        if (child != null) {
            makeChildTransformer(child, actuallyPosition, offset);
        }
        return child;
    }

    private View layoutRightChild(int position, float offset) {
        //获取实际的position
        int actuallyPosition = getActuallyPosition(position);
        View child = showViewArray.get(actuallyPosition);
        if (child != null) {
            makeChildTransformer(child, actuallyPosition, offset);
        }

        return child;
    }

    private void makeChildTransformer(View child, int position, float offset) {
        child.layout(0, 0, child.getMeasuredWidth(), child.getMeasuredHeight());

        float scale;
        scale = 1 - Math.abs(offset) * mEdgeScale;


        // 延x轴移动的距离应该根据center图片决定
        float translateX;

        final int originalChildHeight = (int) (mChildHeight - mChildHeight
                * reflectHeightFraction - reflectGap);

        final float originalChildHeightScale = (float) originalChildHeight
                / child.getHeight();

        final float childHeightScale = originalChildHeightScale * scale;

        final int childWidth = (int) (child.getWidth() * childHeightScale);

        final int centerChildWidth = (int) (child.getWidth() * originalChildHeightScale);

        int leftSpace = ((mWidth >> 1) - paddingLeft) - (centerChildWidth >> 1);
        int rightSpace = (((mWidth >> 1) - paddingRight) - (centerChildWidth >> 1));

        //计算出水平方向的x坐标
        if (offset <= 0)
            translateX = ((float) leftSpace / mSiblingCount)
                    * (mSiblingCount + offset) + paddingLeft;
        else
            translateX = mWidth - ((float) rightSpace / mSiblingCount)
                    * (mSiblingCount - offset) - childWidth
                    - paddingRight;

        //根据offset 算出透明度
        float alpha = 254 - Math.abs(offset) * STANDARD_ALPHA;
        ViewHelper.setAlpha(child, 0);
        //        child.setAlpha(0);
        if (alpha < 0) {
            alpha = 0;
        } else if (alpha > 254) {
            alpha = 254;
        }
        ViewHelper.setAlpha(child, alpha / 254.0f);
        //        child.setAlpha(alpha/254.0f);

        float adjustedChildTranslateY = 0;

        //        //兼容api 10
        //        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB) {
        //            android.animation.ObjectAnimator anim1 = android.animation.ObjectAnimator.ofFloat(child, "scaleX",
        //                    1.0f, childHeightScale);
        //            android.animation.ObjectAnimator anim2 = android.animation.ObjectAnimator.ofFloat(child, "scaleY",
        //                    1.0f, childHeightScale);
        //            android.animation.AnimatorSet animSet = new android.animation.AnimatorSet();
        //            animSet.setDuration(0);
        //            //两个动画同时执行
        //            animSet.playTogether(anim1, anim2);
        //
        //            //显示的调用invalidate
        ////            child.invalidate();
        //            animSet.setTarget(child);
        //            animSet.start();
        //        } else {
        //            ObjectAnimator anim1 = ObjectAnimator.ofFloat(child, "scaleX",
        //                    1.0f, childHeightScale);
        //            ObjectAnimator anim2 = ObjectAnimator.ofFloat(child, "scaleY",
        //                    1.0f, childHeightScale);
        //            AnimatorSet animSet = new AnimatorSet();
        //            animSet.setDuration(0);
        //            //两个动画同时执行
        //            animSet.playTogether(anim1, anim2);
        //
        //            //显示的调用invalidate
        ////            child.invalidate();
        //            animSet.setTarget(child);
        //            animSet.start();
        //        }
        float sc = childHeightScale;
        ViewHelper.setScaleX(child, sc);
        ViewHelper.setScaleY(child, sc);

        ViewHelper.setPivotX(child, 0);
        ViewHelper.setPivotY(child, child.getHeight() / 2);
        //        child.setPivotX(0);
        //        child.setPivotY(child.getHeight()/2);

        ViewHelper.setTranslationX(child, translateX);
        ViewHelper.setTranslationY(child, mChildTranslateY + adjustedChildTranslateY);
        //        child.setTranslationX(translateX);
        //        child.setTranslationY(mChildTranslateY+ adjustedChildTranslateY);
    }

    /**
     * 获取顶部Item position
     *
     * @return int
     */
    public int getTopViewPosition() {
        return getActuallyPosition(lastMidIndex);
    }

    /**
     * 获取顶部Item View
     *
     * @return top view
     */
    public View getTopView() {
        return showViewArray.get(getTopViewPosition());
    }

    /**
     * Convert our index to adapter position.
     *
     * @param index index in CoverFlowView
     * @return adapter position
     */
    private int getActuallyPosition(int index) {
        if (mAdapter == null)
            return index;
        int count = mAdapter.getCount();

        int position = index + mSiblingCount;
        while (position < 0 || position >= count) {
            if (position < 0) {
                position += count;
            } else if (position >= count) {
                position -= count;
            }
        }

        return position;
    }

    public void setAdapter(ACoverFlowAdapter adapter) {
        ACoverFlowAdapter old = mAdapter;
        if (old != null) {
            old.unregisterDataSetObserver(mDataSetObserver);
        }

        stopScroll();
        cancelTouch();
        resetPosition();

        mAdapter = adapter;
        if (mDataSetObserver == null) {
            mDataSetObserver = new AdapterDataSetObserver();
        }
        if (adapter != null) {
            adapter.registerDataSetObserver(mDataSetObserver);
        }

        onAdapterChanged(old, adapter);
        mDataChanged = true;
        requestLayout();
    }

    public ACoverFlowAdapter getAdapter() {
        return mAdapter;
    }

    private void onAdapterChanged(ACoverFlowAdapter oldAdapter, ACoverFlowAdapter newAdapter) {
        // 如果adapter 改变了，view缓存就过期了。清空
        showViewArray.clear();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }

        boolean handled = false;
        int action = event.getAction();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (mScrollState == SCROLL_STATE_SETTLING) {
                    setScrollState(SCROLL_STATE_DRAGGING);
                }
                mTouchCanceled = false;

                touchViewItem = getTopView();
                isOnTopView = inRangeOfView(touchViewItem, event);
                // 如果触摸位置不在顶部view内，直接消费这个event。
                handled = !isOnTopView;
                break;
        }
        return handled;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getAction();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                touchBegan(event);

                if (isOnTopView) {
                    sendLongClickAction();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mTouchCanceled) {
                    break;
                }

                touchMoved(event);

                boolean startScroll = false;
                if (Math.abs(mTouchStartX - event.getX()) > mTouchSlop
                        || Math.abs(mTouchStartY - event.getY()) > mTouchSlop) {
                    removeLongClickAction();
                    touchViewItem = null;
                    isOnTopView = false;
                    startScroll = true;
                }

                if (mScrollState != SCROLL_STATE_DRAGGING) {
                    if (startScroll) {
                        setScrollState(SCROLL_STATE_DRAGGING);
                    }
                }

                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                if (mTouchCanceled) {
                    break;
                }

                removeLongClickAction();
                if (isOnTopView && touchViewItem == getTopView()
                        && inRangeOfView(touchViewItem, event)) {
                    if (mTopViewClickLister != null) {
                        mTopViewClickLister.onClick(getTopViewPosition(), getTopView());
                    }
                }

                touchViewItem = null;
                isOnTopView = false;
                touchEnded(event);

                //不是点击top view。并且启用点击切换。并且点击了左右侧view
                if (!isOnTopView && clickSwitchEnable
                        && Math.abs(mTouchStartX - event.getX()) < mTouchSlop
                        && Math.abs(mTouchStartY - event.getY()) < mTouchSlop
                        && event.getEventTime() - event.getDownTime() < 500) {
                    if (atLeftOfView(getTopView(), event)) {
                        gotoPrevious();
                    } else if (atRightOfView(getTopView(), event)) {
                        gotoForward();
                    }
                }

                break;
        }

        return !mTouchCanceled;
    }

    private void cancelTouch() {
        mTouchCanceled = true;
        setScrollState(SCROLL_STATE_IDLE);
         // 移除long click
        removeLongClickAction();
    }

    private void sendLongClickAction() {
        removeLongClickAction();
        longClickRunnable = new Runnable() {
            @Override
            public void run() {
                touchViewItem = null;
                isOnTopView = false;
                if (mTopViewLongClickLister != null) {
                    mTopViewLongClickLister.onLongClick(getTopViewPosition(), getTopView());
                }
            }
        };
        postDelayed(longClickRunnable, 600L);
    }

    private void removeLongClickAction() {
        if (longClickRunnable != null) {
            removeCallbacks(longClickRunnable);
            longClickRunnable = null;
        }
    }

    private void touchBegan(MotionEvent event) {
        endSettleAnimation();

        float x = event.getX();
        mTouchStartX = x;
        mTouchStartY = event.getY();
        mStartTime = AnimationUtils.currentAnimationTimeMillis();
        mStartOffset = mOffset;

        mTouchStartPos = (x / mWidth) * MOVE_POS_MULTIPLE - 5;
        mTouchStartPos /= 2;

        mVelocity = VelocityTracker.obtain();
        mVelocity.addMovement(event);
    }

    private void touchMoved(MotionEvent event) {
        float pos = (event.getX() / mWidth) * MOVE_POS_MULTIPLE - 5;
        pos /= 2;

        attemptSetOffset(mStartOffset + mTouchStartPos - pos);

        invalidate();
        requestLayout();
        mVelocity.addMovement(event);
    }

    private void touchEnded(MotionEvent event) {
        float pos = (event.getX() / mWidth) * MOVE_POS_MULTIPLE - 5;
        pos /= 2;

        if ((mOffset - Math.floor(mOffset)) != 0) {
            mStartOffset += mTouchStartPos - pos;
            attemptSetOffset(mStartOffset);

            mVelocity.addMovement(event);

            mVelocity.computeCurrentVelocity(1000);
            float speed = mVelocity.getXVelocity();

            speed = (speed / mWidth) * MOVE_SPEED_MULTIPLE;
            if (speed > MAX_SPEED)
                speed = MAX_SPEED;
            else if (speed < -MAX_SPEED)
                speed = -MAX_SPEED;

            startSettleAnimation(-speed);
        } else {
            setScrollState(SCROLL_STATE_IDLE);
        }

        mVelocity.clear();
        mVelocity.recycle();
    }

    private void startSettleAnimation(float speed) {
        if (mSettleAnimationRunnable != null) {
            return;
        }

        float delta = speed * speed / (FRICTION * 2);
        if (speed < 0)
            delta = -delta;

        float nearest = mStartOffset + delta;
        nearest = (float) Math.floor(nearest + 0.5f);

        mStartSpeed = (float) Math.sqrt(Math.abs(nearest - mStartOffset)
                * FRICTION * 2);
        if (nearest < mStartOffset)
            mStartSpeed = -mStartSpeed;

        mDuration = Math.abs(mStartSpeed / FRICTION);
        mStartTime = AnimationUtils.currentAnimationTimeMillis();

        mSettleAnimationRunnable = new Runnable() {
            @Override
            public void run() {
                driveAnimation();
            }
        };
        post(mSettleAnimationRunnable);
        setScrollState(SCROLL_STATE_SETTLING);
    }

    private void driveAnimation() {
        float elapsed = (AnimationUtils.currentAnimationTimeMillis() - mStartTime) / 1000.0f;
        if (elapsed >= mDuration) {
            endSettleAnimation();
        } else {
            updateAnimationAtElapsed(elapsed);
            post(mSettleAnimationRunnable);
        }
    }

    private void endSettleAnimation() {
        if (mScrollState == SCROLL_STATE_SETTLING) {
            attemptSetOffset((float) Math.floor(mOffset + 0.5));

            invalidate();
            requestLayout();

            setScrollState(SCROLL_STATE_IDLE);
        }

        if (mSettleAnimationRunnable != null) {
            removeCallbacks(mSettleAnimationRunnable);
            mSettleAnimationRunnable = null;
        }
    }

    private void updateAnimationAtElapsed(float elapsed) {
        if (elapsed > mDuration)
            elapsed = mDuration;

        float delta = Math.abs(mStartSpeed) * elapsed - FRICTION * elapsed
                * elapsed / 2;
        if (mStartSpeed < 0)
            delta = -delta;

        attemptSetOffset(mStartOffset + delta);
        invalidate();
        requestLayout();
    }

    private void attemptSetOffset(float offset) {
        float old = mOffset;
        if (!mLoopMode) {
            int start = -mSiblingCount;
            int end = mAdapter.getCount() - mSiblingCount - 1;
            if (offset < start) {
                offset = start;
            } else if (offset > end) {
                offset = end;
            }
        }
        mOffset = offset;
    }

    private boolean inRangeOfView(View view, MotionEvent ev) {
        Rect frame = new Rect();
        getViewRect(view, frame);
        return frame.contains((int) ev.getX(), (int) ev.getY());
    }

    private boolean atRightOfView(View view, MotionEvent ev) {
        Rect frame = new Rect();
        getViewRect(view, frame);
        return ev.getX() > frame.right;
    }

    private boolean atLeftOfView(View view, MotionEvent ev) {
        Rect frame = new Rect();
        getViewRect(view, frame);
        return ev.getX() < frame.left;
    }

    private static void getViewRect(View v, Rect rect) {
        rect.left = (int) com.nineoldandroids.view.ViewHelper.getX(v);
        rect.top = (int) com.nineoldandroids.view.ViewHelper.getY(v);
        rect.right = rect.left + v.getWidth();
        rect.bottom = rect.top + v.getHeight();
    }

    public void setScaleRatio(float scaleRatio) {
        if (scaleRatio > 1)
            scaleRatio = 1;
        if (scaleRatio < 0)
            scaleRatio = 0;

        mEdgeScale = (MAX_SCALE - MIN_SCALE) * scaleRatio + MIN_SCALE;
        requestLayout();
    }

    /**
     * 是否可以点击左右侧 来切换上下张
     */
    public void setClick2SwitchEnabled(boolean enabled) {
        clickSwitchEnable = enabled;
    }

    public void setLoopMode(boolean loop) {
        if (loop == mLoopMode) {
            return;
        }

        stopScroll();
        cancelTouch();
        resetPosition();

        this.mLoopMode = loop;

        mDataChanged = true;
        requestLayout();
    }

    /**
     * 翻到前页
     */
    public void gotoPrevious() {
        doSmoothScrollAnimator(-1.0f);
    }

    /**
     * 前进到后一页
     */
    public void gotoForward() {
        doSmoothScrollAnimator(1.0f);
    }

    private void doSmoothScrollAnimator(float target) {
        if (mScrollState != SCROLL_STATE_IDLE) {
            return;
        }
        if (target == 0)
            return;

        float initOffset = mOffset;
        float targetOffset = initOffset + target;
        if (!mLoopMode) { //target offset越界
            int start = -mSiblingCount;
            int end = mAdapter.getCount() - mSiblingCount - 1;
            if (targetOffset < start) {
                return;
            } else if (targetOffset > end) {
                return;
            }
        }

        ValueAnimator animator = ValueAnimator.ofFloat(initOffset, targetOffset);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                attemptSetOffset((Float) animation.getAnimatedValue());

                invalidate();
                requestLayout();
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                setScrollState(SCROLL_STATE_IDLE);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                setScrollState(SCROLL_STATE_IDLE);
            }
        });
        mScrollAnimator = animator;
        setScrollState(SCROLL_STATE_SETTLING);
        animator.setDuration(300).start();
    }

    private void setScrollState(int state) {
        if (state == mScrollState) {
            return;
        }
        mScrollState = state;
        if (state != SCROLL_STATE_SETTLING) {
            stopScrollerInternal();
        }
    }

    public void stopScroll() {
        stopScrollerInternal();
        setScrollState(SCROLL_STATE_IDLE);
    }

    private void stopScrollerInternal() {
        endSettleAnimation();

        if (mScrollAnimator != null)
            mScrollAnimator.cancel();
    }

    public int getScrollState() {
        return mScrollState;
    }

    /**
     * 选择某一个位置
     *
     * @param selection seleciton
     * @param smooth    是否平滑滑动过去，还是直接切换
     */
    public void setSelection(int selection, boolean smooth) {
        if (mAdapter == null)
            return;

        int count = mAdapter.getCount();
        if (selection < 0 || selection >= count) {
            throw new IndexOutOfBoundsException(String.format(Locale.getDefault(),
                    "selection out of bound: selection is %d, range is (%d, %d]", selection, 0,
                    count));
        }
        if (smooth) {
            float offset = mOffset;
            int curPos = getActuallyPosition((int) offset);

            int minDistance;
            //求出当前位置到达selection位置的最短路径
            int distance1 = selection - curPos;

            if (mLoopMode) {
                int distance2 = selection + count - curPos;
                int distance3 = selection - count - curPos;
                minDistance = Math.abs(distance1) < Math.abs(distance2) ? distance1 : distance2;
                minDistance = Math.abs(minDistance) < Math.abs(distance3) ? minDistance : distance3;
            } else {
                minDistance = distance1;
            }

            doSmoothScrollAnimator(minDistance);
        } else {
            cancelTouch();
            stopScroll();

            final int selectionIndex = selection - mSiblingCount;
            mPostOffset = (float) selectionIndex;
            lastMidIndex = selectionIndex;

            mDataChanged = true;
            requestLayout();
        }
    }

    public OnViewOnTopListener getOnViewOnTopListener() {
        return mViewOnTopListener;
    }

    public void setOnViewOnTopListener(OnViewOnTopListener mViewOnTopListener) {
        this.mViewOnTopListener = mViewOnTopListener;
    }

    /**
     * 设置TopView的点击监听
     */
    public void setOnTopViewClickListener(OnTopViewClickListener topViewClickLister) {
        this.mTopViewClickLister = topViewClickLister;
    }

    public OnTopViewClickListener getOnTopViewClickListener() {
        return this.mTopViewClickLister;
    }

    /**
     * 设置TopView的长点击监听
     */
    public void setOnTopViewLongClickListener(OnTopViewLongClickListener topViewLongClickLister) {
        this.mTopViewLongClickLister = topViewLongClickLister;
    }

    public OnTopViewLongClickListener getOnTopViewLongClickListener() {
        return this.mTopViewLongClickLister;
    }


    public interface OnViewOnTopListener {
        void viewOnTop(int position, View itemView);
    }

    public interface OnTopViewClickListener {
        void onClick(int position, View itemView);
    }

    public interface OnTopViewLongClickListener {
        void onLongClick(int position, View itemView);
    }

    private class AdapterDataSetObserver extends DataSetObserver {
        @Override
        public void onChanged() {
            // TODO 理应保持在合适的offset。保证只是数据集改变，位置不变。
            stopScroll();
            cancelTouch();
            resetPosition();

            mDataChanged = true;
            requestLayout();
        }

        private float justifyOffset(float offset, int oldCount, int newCount) {
            while (offset < 0 || offset >= oldCount) {
                if (offset < 0) {
                    offset += oldCount;
                } else if (offset >= oldCount) {
                    offset -= oldCount;
                }
            }
            if (offset > newCount) {
                offset = newCount;
            }

            return offset;
        }

        @Override
        public void onInvalidated() {
            stopScroll();
            cancelTouch();
            resetPosition();

            mDataChanged = true;
            requestLayout();
        }
    }
}
