"""
Author: Xander PENG
Date: 2026-01-30
File: figure_plot.py
Description: Provide some functions for figure plotting
"""
import matplotlib.pyplot as plt
import matplotlib.colors as mcolors
import seaborn as sns
import numpy as np
from scipy import stats
from pathlib import Path


''' Set the matplotlib font globally as Arial '''
plt.rcParams["font.family"] = "Arial"

def darken_color(color, factor=0.7):
    """
    Darken a color by a given factor.
    
    Args:
        color: Color in any matplotlib-accepted format
        factor: Factor to darken (0-1, smaller = darker)
    
    Returns:
        Darkened color as RGB tuple
    """
    rgb = mcolors.to_rgb(color)
    return tuple(c * factor for c in rgb)


def _fit_and_plot_curve(ax, data, x, bin_width, color, label, 
                        fitting_method='normal', n_components=2, kde_bw='scott'):
    """
    Internal helper to fit and plot distribution curves.
    
    Args:
        ax: Matplotlib axis
        data: 1D array of data values
        x: x-values for plotting the curve
        bin_width: Width of histogram bins (for scaling)
        color: Line color
        label: Label for legend
        fitting_method: 'normal', 'kde', or 'gmm'
        n_components: Number of Gaussian components for GMM (default: 2)
        kde_bw: Bandwidth for KDE ('scott', 'silverman', or float; smaller = more detail)
    """
    n = len(data)
    
    if fitting_method == 'normal':
        # Single Gaussian fit
        mu, std = stats.norm.fit(data)
        p = stats.norm.pdf(x, mu, std) * n * bin_width
        ax.plot(x, p, color=color, linewidth=2, linestyle=':', label=f"{label} Fitting")
        
    elif fitting_method == 'kde':
        # Kernel Density Estimation - adapts to any distribution shape
        # kde_bw: smaller value = more detail/peaks; larger = smoother
        try:
            kde = stats.gaussian_kde(data, bw_method=kde_bw)
            p = kde(x) * n * bin_width
            ax.plot(x, p, color=color, linewidth=2, linestyle=':', label=f"{label} Fitting")
        except Exception as e:
            print(f"KDE fitting failed: {e}")
            
    elif fitting_method == 'gmm':
        # Gaussian Mixture Model - good for bimodal/multimodal distributions
        # Plots a single combined curve (sum of all components)
        try:
            from sklearn.mixture import GaussianMixture
            
            data_reshaped = np.array(data).reshape(-1, 1)
            gmm = GaussianMixture(n_components=n_components, random_state=42)
            gmm.fit(data_reshaped)
            
            # Compute combined GMM PDF (single curve)
            x_reshaped = x.reshape(-1, 1)
            log_prob = gmm.score_samples(x_reshaped)
            p = np.exp(log_prob) * n * bin_width
            
            # Plot as single combined curve
            ax.plot(x, p, color=color, linewidth=2, linestyle=':', label=f"{label} Fitting")
                        
        except ImportError:
            print("sklearn not available. Install with: pip install scikit-learn")
            # Fallback to KDE
            kde = stats.gaussian_kde(data, bw_method='scott')
            p = kde(x) * n * bin_width
            ax.plot(x, p, color=color, linewidth=2, linestyle=':', label=f"{label} Fitting")
        except Exception as e:
            print(f"GMM fitting failed: {e}")
    else:
        raise ValueError(f"Unknown fitting_method: {fitting_method}. Use 'normal', 'kde', or 'gmm'.")


def hist_plot(data_df,
              col1,
              col2=None,
              n_bins=50,
              figure_size=(10, 6),
              dpi=350,
              is_fitting=True,
              fitting_method='normal',
              n_components=2,
              kde_bw='scott',
              labels=None,
              figure_folder=None,
              filename=None,
              **kwargs
              ):
    """
    Plot histogram for the specified column(s) with optional distribution fitting.
    If col2 is provided, plot both columns together for comparison.

    Args:
        data_df: DataFrame containing the data
        col1: Name of the first column to plot
        col2: Name of the second column to plot (optional)
        n_bins: Number of bins for histogram (default: 50)
        figure_size: Figure size as tuple (width, height) (default: (10, 6))
        dpi: Figure resolution (default: 350)
        is_fitting: Whether to fit and plot distribution curves (default: True)
        fitting_method: Fitting method - 'normal', 'kde', or 'gmm' (default: 'normal')
            Can be a single string (applied to both columns) or a tuple (method1, method2)
            to use different methods for col1 and col2.
            - 'normal': Single Gaussian (for unimodal distributions)
            - 'kde': Kernel Density Estimation (non-parametric, adapts to any shape)
            - 'gmm': Gaussian Mixture Model (for bimodal/multimodal distributions)
        n_components: Number of Gaussian components for GMM fitting (default: 2)
            Can be a single int or tuple (n1, n2) for col1 and col2 separately.
        kde_bw: Bandwidth for KDE - 'scott', 'silverman', or float (default: 'scott')
            Smaller values (e.g., 0.1-0.3) = more detail/sharper peaks
            Larger values (e.g., 0.5-1.0) = smoother curve
            Can be a tuple (bw1, bw2) for col1 and col2 separately.
        labels: List of labels for legend [label1, label2] (default: column names)
        figure_folder: Folder path to save figure (optional)
        filename: Filename to save figure (optional)
        
    Keyword Args:
        colors: Tuple of colors for (col1, col2) (default: ('steelblue', 'darkgrey'))
        alphas: Tuple of alpha values for (col1, col2) (default: (0.6, 0.8))
        xlabel: X-axis label (default: '')
        ylabel: Y-axis label (default: 'Density')
        label_size: Font size for axis labels (default: 10)
        hide_labels: Whether to hide axis labels (default: True)
        hide_legends: Whether to hide legends (default: False)
        show: Whether to display the plot (default: True)
        
    Examples:
        # Same method for both columns
        hist_plot(df, 'VKT', 'iter0_VKT', fitting_method='normal')
        
        # Different methods: GMM for col1 (bimodal), normal for col2 (unimodal)
        hist_plot(df, 'collab_rate', 'iter0_vkt', fitting_method=('gmm', 'normal'))
        
        # KDE with custom bandwidth (smaller = more detail)
        hist_plot(df, 'metric', fitting_method='kde', kde_bw=0.2)
    """
    # Extract kwargs with defaults
    colors = kwargs.get('colors', ('#1664a2', '#ce5759'))
    alphas = kwargs.get('alphas', (0.8, 0.8))
    xlabel = kwargs.get('xlabel', '')
    ylabel = kwargs.get('ylabel', 'Density')
    label_size = kwargs.get('label_size', 10)
    hide_labels = kwargs.get('hide_labels', True)
    show = kwargs.get('show', True)

    # Parse fitting_method - support tuple for separate control
    if isinstance(fitting_method, tuple):
        fitting_method1 = fitting_method[0]
        fitting_method2 = fitting_method[1] if len(fitting_method) > 1 else fitting_method[0]
    else:
        fitting_method1 = fitting_method2 = fitting_method

    # Parse n_components - support tuple for separate control
    if isinstance(n_components, tuple):
        n_components1 = n_components[0]
        n_components2 = n_components[1] if len(n_components) > 1 else n_components[0]
    else:
        n_components1 = n_components2 = n_components

    # Parse kde_bw - support tuple for separate control
    if isinstance(kde_bw, tuple):
        kde_bw1 = kde_bw[0]
        kde_bw2 = kde_bw[1] if len(kde_bw) > 1 else kde_bw[0]
    else:
        kde_bw1 = kde_bw2 = kde_bw

    # Validate columns
    if col1 not in data_df.columns:
        raise ValueError(f"Column '{col1}' not found in data_df.")
    if col2 is not None and col2 not in data_df.columns:
        raise ValueError(f"Column '{col2}' not found in data_df.")

    # Get data series (drop NaN values)
    data1 = data_df[col1].dropna()
    data2 = data_df[col2].dropna() if col2 is not None else None

    # Set labels
    if labels is None:
        label1 = col1
        label2 = col2 if col2 is not None else None
    else:
        label1 = labels[0]
        label2 = labels[1] if len(labels) > 1 and col2 is not None else None

    # Create figure
    fig, ax = plt.subplots(figsize=figure_size, dpi=dpi)

    # Calculate bin range across all data
    if col2 is not None:
        min_val = min(data1.min(), data2.min())
        max_val = max(data1.max(), data2.max())
    else:
        min_val = data1.min()
        max_val = data1.max()

    bins = np.linspace(min_val, max_val, n_bins)
    bin_width = bins[1] - bins[0]

    # Plot histograms (col2 first if exists, so col1 is on top)
    if col2 is not None:
        plt.hist(data2, bins=bins, color=colors[1], alpha=alphas[1], label=label2)
    plt.hist(data1, bins=bins, color=colors[0], alpha=alphas[0], label=label1)

    # Fit and plot distribution curves
    if is_fitting:
        xmin, xmax = plt.xlim()
        x = np.linspace(xmin, xmax, 1000)

        # Fit col1
        _fit_and_plot_curve(ax, data1, x, bin_width, 
                           darken_color(colors[0], 0.5), label1,
                           fitting_method1, n_components1, kde_bw1)

        # Fit col2 if provided
        if col2 is not None:
            _fit_and_plot_curve(ax, data2, x, bin_width,
                               darken_color(colors[1]), label2,
                               fitting_method2, n_components2, kde_bw2)

    # Hide the right and top spines
    ax.spines["right"].set_visible(False)
    ax.spines["top"].set_visible(False)

    # Set labels
    ax.set_xlabel(xlabel, fontsize=label_size, fontweight="bold")
    ax.set_ylabel(ylabel, fontsize=label_size, fontweight="bold")
    if hide_labels:
        ax.set_xlabel('')
        ax.set_ylabel('')

    # Set tick font sizes
    plt.xticks(fontsize=10)
    plt.yticks(fontsize=10)

    # Add legend
    if not kwargs.get('hide_legends'):
        ax.legend()

    plt.tight_layout()

    # Save figure if path provided
    if figure_folder is not None and filename is not None:
        save_path = Path(figure_folder) / filename
        plt.savefig(str(save_path),
                    bbox_inches='tight',
                    pad_inches=0,
                    transparent=True)

    if show:
        plt.show()


def joint_scatter_plot(data_df,
                       x_col,
                       y_col,
                       n_bins=20,
                       figure_size=(8, 8),
                       dpi=350,
                       marginal_type='hist',
                       kde_bw='scott',
                       labels=None,
                       figure_folder=None,
                       filename=None,
                       # Second dataset (optional)
                       data_df2=None,
                       x_col2=None,
                       y_col2=None,
                       # Grouping columns for first dataset (optional)
                       color_group_col=None,
                       size_group_col=None,
                       **kwargs
                       ):
    """
    Create a joint scatter plot with marginal distributions (similar to seaborn jointplot).
    Main plot: scatter of x_col vs y_col
    Top marginal: histogram/KDE of x_col
    Right marginal: histogram/KDE of y_col

    Args:
        data_df: DataFrame containing the data
        x_col: Name of the column for x-axis (e.g., 'carrier_score')
        y_col: Name of the column for y-axis (e.g., 'receiver_score')
        n_bins: Number of bins for histogram (default: 20)
        figure_size: Figure size as tuple (width, height) (default: (8, 8))
        dpi: Figure resolution (default: 350)
        marginal_type: Type of marginal plot - 'hist', 'kde', or 'both' (default: 'hist')
        kde_bw: Bandwidth for KDE - 'scott', 'silverman', or float (default: 'scott')
        labels: Tuple of labels for (x_axis, y_axis) (default: column names)
        figure_folder: Folder path to save figure (optional)
        filename: Filename to save figure (optional)
        
        data_df2: Optional second DataFrame for overlay scatter (no marginal plots)
        x_col2: Column name for x-axis of second dataset
        y_col2: Column name for y-axis of second dataset
        
        color_group_col: Column name to color first dataset points by value (continuous colormap)
        size_group_col: Column name to size first dataset points by value

    Keyword Args:
        # First dataset scatter options
        scatter_color: Color for scatter points (default: '#1664a2', ignored if color_group_col is set)
        scatter_alpha: Alpha for scatter points (default: 0.6)
        scatter_size: Size for scatter points (default: 30, ignored if size_group_col is set)
        scatter_marker: Marker style for first dataset (default: 'o')
        scatter_edgecolor: Edge color for scatter points (default: 'white')
        scatter_linewidth: Edge line width for scatter points (default: 0.5)
        
        # Color grouping options (when color_group_col is set)
        cmap: Colormap name or object (default: auto-detect based on data type)
            - For categorical: auto-selects 'tab10'/'tab20' based on number of categories
            - For continuous: defaults to 'viridis'
        cmap_color_list: List of colors to create custom colormap (e.g., ['red', 'green', 'blue'])
            When provided, creates a ListedColormap from these colors.
        cmap_vmin: Min value for colormap normalization (default: auto, only for continuous)
        cmap_vmax: Max value for colormap normalization (default: auto, only for continuous)
        show_colorbar: Whether to show colorbar for continuous variables (default: True)
        show_color_legend: Whether to show legend for categorical variables (default: True)
        color_legend_loc: Location for categorical color legend (default: 'upper left')
        colorbar_label: Label for colorbar/legend title (default: color_group_col)
        colorbar_shrink: Shrink factor for colorbar (default: 0.6)
        categorical_threshold: Max unique values to treat as categorical (default: 12)
        
        # Size grouping options (when size_group_col is set)
        size_min: Minimum point size (default: 10)
        size_max: Maximum point size (default: 200)
        size_norm_vmin: Min value for size normalization (default: auto)
        size_norm_vmax: Max value for size normalization (default: auto)
        show_size_legend: Whether to show size legend (default: True)
        size_legend_title: Title for size legend (default: size_group_col)
        size_legend_loc: Location for size legend (default: 'upper right')
        size_legend_num: Number of entries in size legend (default: 4)
        
        # Second dataset scatter options
        scatter2_color: Color for second dataset points (default: '#ce5759')
        scatter2_alpha: Alpha for second dataset points (default: 0.6)
        scatter2_size: Size for second dataset points (default: 30)
        scatter2_marker: Marker style for second dataset (default: 'x')
        scatter2_edgecolor: Edge color for second dataset (default: 'white')
        scatter2_linewidth: Edge line width for second dataset (default: 0.5)
        scatter2_label: Label for second dataset in legend (default: None)
        
        # Histogram options
        hist_color: Color for histogram bars (default: scatter_color)
        hist_alpha: Alpha for histogram bars (default: 0.7)
        
        # KDE line options
        kde_color: Color for KDE line (default: darkened scatter_color)
        kde_linewidth: Line width for KDE (default: 2)
        kde_linestyle: Line style for KDE (default: '-')
        kde_alpha: Alpha for KDE line (default: 1.0)
        
        # Labels and display options
        xlabel: X-axis label (default: x_col)
        ylabel: Y-axis label (default: y_col)
        label_size: Font size for axis labels (default: 12)
        hide_labels: Whether to hide axis labels (default: False)
        marginal_label: Label for marginal axes (default: 'Density')
        marginal_label_size: Font size for marginal labels (default: 10)
        hide_marginal_labels: Whether to hide marginal labels (default: False)
        show: Whether to display the plot (default: True)
        add_regression: Whether to add regression line (default: False)
        show_corr: Whether to show correlation coefficient (default: True)

    Returns:
        fig: matplotlib Figure object
        axes: dict with 'main', 'top', 'right' axes

    Examples:
        # Basic usage
        joint_scatter_plot(df, 'final_carrier_score', 'total_receiver_scores',
                          marginal_type='both', show_corr=True)
        
        # With color grouping by a column
        joint_scatter_plot(df, 'carrier_score', 'receiver_score',
                          color_group_col='penalty', cmap='plasma')
        
        # With size grouping
        joint_scatter_plot(df, 'carrier_score', 'receiver_score',
                          size_group_col='cost_savings', size_min=20, size_max=150)
        
        # With second dataset overlay
        joint_scatter_plot(df1, 'x', 'y', data_df2=df2, x_col2='x', y_col2='y',
                          scatter2_marker='s', scatter2_color='red')
        
        # Combined: color + size grouping + second dataset
        joint_scatter_plot(df1, 'x', 'y',
                          color_group_col='penalty', size_group_col='savings',
                          data_df2=df2, x_col2='x2', y_col2='y2')
        
        # With custom color list for categorical variable
        joint_scatter_plot(df, 'x', 'y', color_group_col='category',
                          cmap_color_list=['#e41a1c', '#377eb8', '#4daf4a', '#984ea3'])
        
        # Categorical with auto-detected colormap
        joint_scatter_plot(df, 'x', 'y', color_group_col='depot_location')  # auto: tab10
    """
    # Extract kwargs with defaults - First dataset
    scatter_color = kwargs.get('scatter_color', '#1664a2')
    scatter_alpha = kwargs.get('scatter_alpha', 0.6)
    scatter_size = kwargs.get('scatter_size', 30)
    scatter_marker = kwargs.get('scatter_marker', 'o')
    scatter_edgecolor = kwargs.get('scatter_edgecolor', 'white')
    scatter_linewidth = kwargs.get('scatter_linewidth', 0.5)
    
    # Color grouping options
    cmap = kwargs.get('cmap', None)  # None = auto-detect
    cmap_color_list = kwargs.get('cmap_color_list', None)  # User-provided color list
    cmap_vmin = kwargs.get('cmap_vmin', None)
    cmap_vmax = kwargs.get('cmap_vmax', None)
    show_colorbar = kwargs.get('show_colorbar', True)
    show_color_legend = kwargs.get('show_color_legend', True)  # For categorical
    color_legend_loc = kwargs.get('color_legend_loc', 'upper left')
    colorbar_label = kwargs.get('colorbar_label', color_group_col)
    colorbar_shrink = kwargs.get('colorbar_shrink', 0.6)
    categorical_threshold = kwargs.get('categorical_threshold', 12)  # Max unique values to treat as categorical
    
    # Size grouping options
    size_min = kwargs.get('size_min', 10)
    size_max = kwargs.get('size_max', 200)
    size_norm_vmin = kwargs.get('size_norm_vmin', None)
    size_norm_vmax = kwargs.get('size_norm_vmax', None)
    
    # Second dataset options
    scatter2_color = kwargs.get('scatter2_color', '#ce5759')
    scatter2_alpha = kwargs.get('scatter2_alpha', 0.6)
    scatter2_size = kwargs.get('scatter2_size', 30)
    scatter2_marker = kwargs.get('scatter2_marker', 'x')
    scatter2_edgecolor = kwargs.get('scatter2_edgecolor', 'white')
    scatter2_linewidth = kwargs.get('scatter2_linewidth', 0.5)
    scatter2_label = kwargs.get('scatter2_label', None)
    
    # Histogram options
    hist_color = kwargs.get('hist_color', scatter_color)
    hist_alpha = kwargs.get('hist_alpha', 0.7)
    
    # KDE line options
    kde_color = kwargs.get('kde_color', darken_color(scatter_color, 0.6))
    kde_linewidth = kwargs.get('kde_linewidth', 2)
    kde_linestyle = kwargs.get('kde_linestyle', '-')
    kde_alpha = kwargs.get('kde_alpha', 1.0)
    
    # Labels and display
    xlabel = kwargs.get('xlabel', x_col)
    ylabel = kwargs.get('ylabel', y_col)
    label_size = kwargs.get('label_size', 12)
    hide_labels = kwargs.get('hide_labels', False)
    marginal_label = kwargs.get('marginal_label', 'Density')
    marginal_label_size = kwargs.get('marginal_label_size', 10)
    hide_marginal_labels = kwargs.get('hide_marginal_labels', False)
    show = kwargs.get('show', True)
    add_regression = kwargs.get('add_regression', False)
    show_corr = kwargs.get('show_corr', True)

    # Validate columns
    if x_col not in data_df.columns:
        raise ValueError(f"Column '{x_col}' not found in data_df.")
    if y_col not in data_df.columns:
        raise ValueError(f"Column '{y_col}' not found in data_df.")
    if color_group_col is not None and color_group_col not in data_df.columns:
        raise ValueError(f"Column '{color_group_col}' not found in data_df.")
    if size_group_col is not None and size_group_col not in data_df.columns:
        raise ValueError(f"Column '{size_group_col}' not found in data_df.")

    # Get data (drop NaN values for both columns together)
    valid_mask = data_df[x_col].notna() & data_df[y_col].notna()
    if color_group_col is not None:
        valid_mask = valid_mask & data_df[color_group_col].notna()
    if size_group_col is not None:
        valid_mask = valid_mask & data_df[size_group_col].notna()
    
    x_data = data_df.loc[valid_mask, x_col].values
    y_data = data_df.loc[valid_mask, y_col].values
    
    # Get color/size grouping data if specified
    color_data = data_df.loc[valid_mask, color_group_col].values if color_group_col else None
    size_data = data_df.loc[valid_mask, size_group_col].values if size_group_col else None

    # Set labels
    if labels is not None:
        xlabel = labels[0]
        ylabel = labels[1] if len(labels) > 1 else y_col

    # Create figure with GridSpec for layout
    fig = plt.figure(figsize=figure_size, dpi=dpi)
    
    # Define grid: main scatter is larger, marginals are smaller
    gs = fig.add_gridspec(4, 4, hspace=0.05, wspace=0.05)
    
    ax_main = fig.add_subplot(gs[1:4, 0:3])   # Main scatter plot (bottom-left 3x3)
    ax_top = fig.add_subplot(gs[0, 0:3], sharex=ax_main)    # Top marginal
    ax_right = fig.add_subplot(gs[1:4, 3], sharey=ax_main)  # Right marginal

    # ========== Main scatter plot - First dataset ==========
    
    # Determine if color_group_col is categorical or continuous
    is_categorical = False
    unique_color_values = None
    color_to_idx = None
    final_cmap = None
    
    if color_group_col is not None:
        # Check if categorical: string/object dtype, or few unique values
        unique_color_values = np.unique(color_data[~np.isnan(color_data)] if np.issubdtype(color_data.dtype, np.number) 
                                        else color_data)
        n_unique = len(unique_color_values)
        
        # Determine if categorical
        if not np.issubdtype(color_data.dtype, np.floating) or n_unique <= categorical_threshold:
            is_categorical = True
        
        if is_categorical:
            # Create mapping from unique values to indices
            color_to_idx = {val: i for i, val in enumerate(unique_color_values)}
            c_values = np.array([color_to_idx[v] for v in color_data])
            
            # Determine colormap for categorical data
            if cmap_color_list is not None:
                # User provided color list - create custom colormap
                from matplotlib.colors import ListedColormap
                # Ensure we have enough colors
                if len(cmap_color_list) < n_unique:
                    # Cycle colors if not enough
                    extended_colors = (cmap_color_list * ((n_unique // len(cmap_color_list)) + 1))[:n_unique]
                    final_cmap = ListedColormap(extended_colors)
                else:
                    final_cmap = ListedColormap(cmap_color_list[:n_unique])
            elif cmap is not None:
                # User provided cmap name
                if isinstance(cmap, str):
                    base_cmap = plt.cm.get_cmap(cmap)
                    # Sample n_unique colors from the colormap
                    if hasattr(base_cmap, 'N') and base_cmap.N >= n_unique:
                        # Discrete colormap (like tab10, Set1, etc.)
                        colors = [base_cmap(i) for i in range(n_unique)]
                    else:
                        # Continuous colormap - sample evenly
                        colors = [base_cmap(i / (n_unique - 1) if n_unique > 1 else 0.5) for i in range(n_unique)]
                    from matplotlib.colors import ListedColormap
                    final_cmap = ListedColormap(colors)
                else:
                    final_cmap = cmap
            else:
                # Auto-select categorical colormap based on number of categories
                if n_unique <= 10:
                    final_cmap = plt.cm.get_cmap('tab10')
                elif n_unique <= 20:
                    final_cmap = plt.cm.get_cmap('tab20')
                else:
                    # For many categories, use a continuous colormap sampled discretely
                    base_cmap = plt.cm.get_cmap('viridis')
                    colors = [base_cmap(i / (n_unique - 1)) for i in range(n_unique)]
                    from matplotlib.colors import ListedColormap
                    final_cmap = ListedColormap(colors)
            
            cmap_vmin = 0
            cmap_vmax = n_unique - 1
        else:
            # Continuous variable
            c_values = color_data
            if cmap_vmin is None:
                cmap_vmin = np.nanmin(c_values)
            if cmap_vmax is None:
                cmap_vmax = np.nanmax(c_values)
            
            # Use provided cmap or default to viridis
            if cmap is None:
                final_cmap = 'viridis'
            else:
                final_cmap = cmap
    else:
        c_values = scatter_color
    
    # Determine sizes for scatter
    if size_group_col is not None:
        # Normalize size_data to [size_min, size_max]
        s_values = size_data
        if size_norm_vmin is None:
            size_norm_vmin = np.nanmin(s_values)
        if size_norm_vmax is None:
            size_norm_vmax = np.nanmax(s_values)
        
        # Normalize to [0, 1] then scale to [size_min, size_max]
        if size_norm_vmax > size_norm_vmin:
            s_normalized = (s_values - size_norm_vmin) / (size_norm_vmax - size_norm_vmin)
        else:
            s_normalized = np.ones_like(s_values) * 0.5
        s_sizes = size_min + s_normalized * (size_max - size_min)
    else:
        s_sizes = scatter_size
    
    # Plot first dataset
    if color_group_col is not None:
        scatter1 = ax_main.scatter(x_data, y_data, c=c_values, cmap=final_cmap,
                                   vmin=cmap_vmin, vmax=cmap_vmax,
                                   alpha=scatter_alpha, s=s_sizes, 
                                   marker=scatter_marker,
                                   edgecolors=scatter_edgecolor, 
                                   linewidth=scatter_linewidth)
        
        if is_categorical:
            # Add color legend for categorical variable
            if show_color_legend:
                color_legend_handles = []
                color_legend_labels = []
                
                for i, val in enumerate(unique_color_values):
                    color = final_cmap(i / (len(unique_color_values) - 1) if len(unique_color_values) > 1 else 0)
                    handle = ax_main.scatter([], [], c=[color], s=scatter_size,
                                            marker=scatter_marker, alpha=scatter_alpha,
                                            edgecolors=scatter_edgecolor, linewidth=scatter_linewidth)
                    color_legend_handles.append(handle)
                    # Format label based on type
                    if isinstance(val, (int, np.integer)):
                        color_legend_labels.append(str(int(val)))
                    elif isinstance(val, (float, np.floating)):
                        color_legend_labels.append(f'{val:.3g}')
                    else:
                        color_legend_labels.append(str(val))
                
                color_legend = ax_main.legend(color_legend_handles, color_legend_labels,
                                              title=colorbar_label if colorbar_label else color_group_col,
                                              loc=color_legend_loc, framealpha=0.9, 
                                              fontsize=9, title_fontsize=10)
                ax_main.add_artist(color_legend)
        else:
            # Add colorbar for continuous variable
            if show_colorbar:
                cbar = fig.colorbar(scatter1, ax=ax_main, shrink=colorbar_shrink, pad=0.02)
                if colorbar_label:
                    cbar.set_label(colorbar_label, fontsize=10)
    else:
        scatter1 = ax_main.scatter(x_data, y_data, c=c_values, alpha=scatter_alpha, 
                                   s=s_sizes, marker=scatter_marker,
                                   edgecolors=scatter_edgecolor, 
                                   linewidth=scatter_linewidth)
    
    # ========== Size Legend (when size_group_col is set) ==========
    if size_group_col is not None:
        show_size_legend = kwargs.get('show_size_legend', True)
        size_legend_title = kwargs.get('size_legend_title', size_group_col)
        size_legend_loc = kwargs.get('size_legend_loc', 'upper right')
        size_legend_num = kwargs.get('size_legend_num', 4)  # Number of legend entries
        
        if show_size_legend:
            # Create legend handles for different sizes
            size_legend_values = np.linspace(size_norm_vmin, size_norm_vmax, size_legend_num)
            size_legend_sizes = size_min + ((size_legend_values - size_norm_vmin) / 
                                            (size_norm_vmax - size_norm_vmin + 1e-10)) * (size_max - size_min)
            
            # Determine color for legend markers
            if color_group_col is not None and final_cmap is not None:
                # Use middle color from colormap
                if hasattr(final_cmap, '__call__'):
                    legend_color = final_cmap(0.5)
                else:
                    legend_color = plt.cm.get_cmap(final_cmap)(0.5)
            else:
                legend_color = scatter_color
            
            # Create scatter handles for legend
            legend_handles = []
            for val, sz in zip(size_legend_values, size_legend_sizes):
                handle = ax_main.scatter([], [], s=sz, c=[legend_color], 
                                        marker=scatter_marker, alpha=scatter_alpha,
                                        edgecolors=scatter_edgecolor, linewidth=scatter_linewidth)
                legend_handles.append(handle)
            
            # Format legend labels
            legend_labels = [f'{val:.2g}' for val in size_legend_values]
            
            # Add size legend
            size_legend = ax_main.legend(legend_handles, legend_labels, 
                                         title=size_legend_title, loc=size_legend_loc,
                                         framealpha=0.9, fontsize=9, title_fontsize=10,
                                         labelspacing=1.2, handletextpad=1.5)
            ax_main.add_artist(size_legend)
    
    # ========== Second dataset (optional) ==========
    if data_df2 is not None and x_col2 is not None and y_col2 is not None:
        if x_col2 not in data_df2.columns:
            raise ValueError(f"Column '{x_col2}' not found in data_df2.")
        if y_col2 not in data_df2.columns:
            raise ValueError(f"Column '{y_col2}' not found in data_df2.")
        
        valid_mask2 = data_df2[x_col2].notna() & data_df2[y_col2].notna()
        x_data2 = data_df2.loc[valid_mask2, x_col2].values
        y_data2 = data_df2.loc[valid_mask2, y_col2].values
        
        ax_main.scatter(x_data2, y_data2, c=scatter2_color, alpha=scatter2_alpha,
                        s=scatter2_size, marker=scatter2_marker,
                        edgecolors=scatter2_edgecolor, linewidth=scatter2_linewidth,
                        label=scatter2_label)
        
        if scatter2_label:
            ax_main.legend(loc='best', fontsize=9)
    
    # Add regression line if requested
    if add_regression:
        slope, intercept, r_value, p_value, std_err = stats.linregress(x_data, y_data)
        x_fit = np.linspace(x_data.min(), x_data.max(), 100)
        y_fit = slope * x_fit + intercept
        ax_main.plot(x_fit, y_fit, color='red', linewidth=2, linestyle='--', 
                     label=f'y = {slope:.3f}x + {intercept:.3f}')
        ax_main.legend(loc='best', fontsize=9)
    
    # Show correlation coefficient
    if show_corr:
        corr = np.corrcoef(x_data, y_data)[0, 1]
        ax_main.annotate(f'r = {corr:.3f}', xy=(0.05, 0.95), xycoords='axes fraction',
                        fontsize=11, fontweight='bold', 
                        bbox=dict(boxstyle='round', facecolor='white', alpha=0.8))

    ax_main.grid(True, alpha=0.3)
    ax_main.spines['top'].set_visible(False)
    ax_main.spines['right'].set_visible(False)

    # ========== Top marginal (x distribution) - Only for first dataset ==========
    x_bins = np.linspace(x_data.min(), x_data.max(), n_bins)
    
    # Use scatter_color for hist if no color grouping, otherwise use a neutral color
    marginal_hist_color = hist_color
    marginal_kde_color = kde_color if color_group_col is None else darken_color(marginal_hist_color, 0.6)
    
    if marginal_type in ['hist', 'both']:
        ax_top.hist(x_data, bins=x_bins, color=marginal_hist_color, alpha=hist_alpha, 
                    edgecolor='white', linewidth=0.5)
    
    if marginal_type in ['kde', 'both']:
        try:
            kde_x = stats.gaussian_kde(x_data, bw_method=kde_bw)
            x_smooth = np.linspace(x_data.min(), x_data.max(), 200)
            if marginal_type == 'kde':
                # Scale KDE to fill the axis nicely
                ax_top.fill_between(x_smooth, kde_x(x_smooth), alpha=hist_alpha, color=marginal_hist_color)
                ax_top.plot(x_smooth, kde_x(x_smooth), color=marginal_kde_color, 
                           linewidth=kde_linewidth, linestyle=kde_linestyle, alpha=kde_alpha)
            else:
                # Scale KDE to match histogram
                bin_width = x_bins[1] - x_bins[0]
                ax_top.plot(x_smooth, kde_x(x_smooth) * len(x_data) * bin_width, 
                           color=marginal_kde_color, linewidth=kde_linewidth,
                           linestyle=kde_linestyle, alpha=kde_alpha)
        except Exception as e:
            print(f"KDE fitting for x failed: {e}")

    ax_top.tick_params(labelbottom=False)
    ax_top.spines['top'].set_visible(False)
    ax_top.spines['right'].set_visible(False)
    ax_top.spines['bottom'].set_visible(False)
    
    if not hide_marginal_labels:
        ax_top.set_ylabel(marginal_label, fontsize=marginal_label_size, fontweight='bold')
        # Align ylabel with main plot's ylabel by setting same x coordinate
        ax_top.yaxis.set_label_coords(-0.1, 0.5)

    # ========== Right marginal (y distribution) - Only for first dataset ==========
    y_bins = np.linspace(y_data.min(), y_data.max(), n_bins)
    
    if marginal_type in ['hist', 'both']:
        ax_right.hist(y_data, bins=y_bins, color=marginal_hist_color, alpha=hist_alpha,
                      edgecolor='white', linewidth=0.5, orientation='horizontal')
    
    if marginal_type in ['kde', 'both']:
        try:
            kde_y = stats.gaussian_kde(y_data, bw_method=kde_bw)
            y_smooth = np.linspace(y_data.min(), y_data.max(), 200)
            if marginal_type == 'kde':
                ax_right.fill_betweenx(y_smooth, kde_y(y_smooth), alpha=hist_alpha, color=marginal_hist_color)
                ax_right.plot(kde_y(y_smooth), y_smooth, color=marginal_kde_color, 
                             linewidth=kde_linewidth, linestyle=kde_linestyle, alpha=kde_alpha)
            else:
                bin_width = y_bins[1] - y_bins[0]
                ax_right.plot(kde_y(y_smooth) * len(y_data) * bin_width, y_smooth,
                             color=marginal_kde_color, linewidth=kde_linewidth,
                             linestyle=kde_linestyle, alpha=kde_alpha)
        except Exception as e:
            print(f"KDE fitting for y failed: {e}")

    ax_right.tick_params(labelleft=False)
    ax_right.spines['top'].set_visible(False)
    ax_right.spines['right'].set_visible(False)
    ax_right.spines['left'].set_visible(False)
    
    if not hide_marginal_labels:
        ax_right.set_xlabel(marginal_label, fontsize=marginal_label_size, fontweight='bold')

    # ========== Labels ==========
    if not hide_labels:
        ax_main.set_xlabel(xlabel, fontsize=label_size, fontweight='bold')
        ax_main.set_ylabel(ylabel, fontsize=label_size, fontweight='bold')

    plt.tight_layout()

    # Save figure if path provided
    if figure_folder is not None and filename is not None:
        save_path = Path(figure_folder) / filename
        plt.savefig(str(save_path),
                    bbox_inches='tight',
                    pad_inches=0,
                    transparent=True)

    if show:
        plt.show()

    return fig, {'main': ax_main, 'top': ax_top, 'right': ax_right}


def scatter_regression_plot(data_df,
                            x_col,
                            y_col,
                            # Second dataset (optional)
                            data_df2=None,
                            x_col2=None,
                            y_col2=None,
                            # Figure settings
                            figure_size=(8, 6),
                            dpi=350,
                            # Regression options
                            add_regression=False,
                            show_equation=True,
                            show_r2=True,
                            show_corr=False,
                            # Second dataset regression
                            add_regression2=False,
                            show_equation2=True,
                            show_r2_2=True,
                            # Labels
                            labels=None,
                            figure_folder=None,
                            filename=None,
                            **kwargs
                            ):
    """
    Create a scatter plot with optional regression line and statistics.
    
    Args:
        data_df: DataFrame containing the first dataset
        x_col: Column name for x-axis
        y_col: Column name for y-axis
        
        data_df2: Optional second DataFrame for overlay scatter
        x_col2: Column name for x-axis of second dataset (default: same as x_col)
        y_col2: Column name for y-axis of second dataset (default: same as y_col)
        
        figure_size: Figure size as tuple (width, height) (default: (8, 6))
        dpi: Figure resolution (default: 350)
        
        add_regression: Whether to add regression line for first dataset (default: False)
        show_equation: Whether to show regression equation (default: True)
        show_r2: Whether to show R² value (default: True)
        show_corr: Whether to show Pearson correlation coefficient (default: False)
        
        add_regression2: Whether to add regression line for second dataset (default: False)
        show_equation2: Whether to show regression equation for second dataset (default: True)
        show_r2_2: Whether to show R² for second dataset (default: True)
        
        labels: Tuple of labels for (x_axis, y_axis) (default: column names)
        figure_folder: Folder path to save figure (optional)
        filename: Filename to save figure (optional)

    Keyword Args:
        # First dataset scatter options
        scatter_color: Color for scatter points (default: '#1664a2')
        scatter_alpha: Alpha for scatter points (default: 0.6)
        scatter_size: Size for scatter points (default: 40)
        scatter_marker: Marker style (default: 'o')
        scatter_edgecolor: Edge color for scatter points (default: 'white')
        scatter_linewidth: Edge line width (default: 0.5)
        scatter_label: Label for first dataset in legend (default: None)
        
        # First dataset regression line options
        reg_color: Color for regression line (default: darkened scatter_color)
        reg_linewidth: Line width for regression line (default: 2)
        reg_linestyle: Line style for regression line (default: '--')
        reg_alpha: Alpha for regression line (default: 1.0)
        
        # Second dataset scatter options
        scatter2_color: Color for second dataset points (default: '#ce5759')
        scatter2_alpha: Alpha for second dataset points (default: 0.6)
        scatter2_size: Size for second dataset points (default: 40)
        scatter2_marker: Marker style for second dataset (default: 's')
        scatter2_edgecolor: Edge color (default: 'white')
        scatter2_linewidth: Edge line width (default: 0.5)
        scatter2_label: Label for second dataset in legend (default: None)
        
        # Second dataset regression line options
        reg2_color: Color for second regression line (default: darkened scatter2_color)
        reg2_linewidth: Line width (default: 2)
        reg2_linestyle: Line style (default: ':')
        reg2_alpha: Alpha (default: 1.0)
        
        # Annotation options
        annotation_fontsize: Font size for equation/R² annotation (default: 10)
        annotation_loc: Location for annotation - 'auto', 'upper left', 'upper right', 
                        'lower left', 'lower right' (default: 'auto')
        annotation2_loc: Location for second dataset annotation (default: 'auto')
        
        # Labels and display options
        xlabel: X-axis label (default: x_col)
        ylabel: Y-axis label (default: y_col)
        title: Plot title (default: '')
        label_size: Font size for axis labels (default: 12)
        title_size: Font size for title (default: 14)
        hide_labels: Whether to hide axis labels (default: False)
        show_legend: Whether to show legend (default: True when labels provided)
        legend_loc: Location for legend (default: 'best')
        show_grid: Whether to show grid (default: True)
        grid_alpha: Alpha for grid (default: 0.3)
        show: Whether to display the plot (default: True)

    Returns:
        fig: matplotlib Figure object
        ax: matplotlib Axes object
        stats_dict: Dictionary containing regression statistics

    Examples:
        # Basic scatter
        scatter_regression_plot(df, 'x', 'y')
        
        # With regression line
        scatter_regression_plot(df, 'x', 'y', add_regression=True)
        
        # Two datasets comparison
        scatter_regression_plot(df1, 'x', 'y', 
                               data_df2=df2, x_col2='x', y_col2='y',
                               scatter_label='Dataset 1', scatter2_label='Dataset 2',
                               add_regression=True, add_regression2=True)
        
        # Custom styling
        scatter_regression_plot(df, 'x', 'y',
                               scatter_color='green', scatter_marker='^', scatter_size=60,
                               add_regression=True, reg_linestyle='-', reg_color='darkgreen')
    """
    # Extract kwargs with defaults - First dataset scatter
    scatter_color = kwargs.get('scatter_color', '#1664a2')
    scatter_alpha = kwargs.get('scatter_alpha', 0.6)
    scatter_size = kwargs.get('scatter_size', 40)
    scatter_marker = kwargs.get('scatter_marker', 'o')
    scatter_edgecolor = kwargs.get('scatter_edgecolor', 'white')
    scatter_linewidth = kwargs.get('scatter_linewidth', 0.5)
    scatter_label = kwargs.get('scatter_label', None)
    
    # First dataset regression line
    reg_color = kwargs.get('reg_color', darken_color(scatter_color, 0.6))
    reg_linewidth = kwargs.get('reg_linewidth', 2)
    reg_linestyle = kwargs.get('reg_linestyle', '--')
    reg_alpha = kwargs.get('reg_alpha', 1.0)
    
    # Second dataset scatter
    scatter2_color = kwargs.get('scatter2_color', '#ce5759')
    scatter2_alpha = kwargs.get('scatter2_alpha', 0.6)
    scatter2_size = kwargs.get('scatter2_size', 40)
    scatter2_marker = kwargs.get('scatter2_marker', 's')
    scatter2_edgecolor = kwargs.get('scatter2_edgecolor', 'white')
    scatter2_linewidth = kwargs.get('scatter2_linewidth', 0.5)
    scatter2_label = kwargs.get('scatter2_label', None)
    
    # Second dataset regression line
    reg2_color = kwargs.get('reg2_color', darken_color(scatter2_color, 0.6))
    reg2_linewidth = kwargs.get('reg2_linewidth', 2)
    reg2_linestyle = kwargs.get('reg2_linestyle', ':')
    reg2_alpha = kwargs.get('reg2_alpha', 1.0)
    
    # Annotation options
    annotation_fontsize = kwargs.get('annotation_fontsize', 10)
    annotation_loc = kwargs.get('annotation_loc', 'auto')
    annotation2_loc = kwargs.get('annotation2_loc', 'auto')
    
    # Labels and display
    xlabel = kwargs.get('xlabel', x_col)
    ylabel = kwargs.get('ylabel', y_col)
    title = kwargs.get('title', '')
    label_size = kwargs.get('label_size', 12)
    title_size = kwargs.get('title_size', 14)
    hide_labels = kwargs.get('hide_labels', False)
    show_legend = kwargs.get('show_legend', None)  # Auto-detect
    legend_loc = kwargs.get('legend_loc', 'best')
    show_grid = kwargs.get('show_grid', True)
    grid_alpha = kwargs.get('grid_alpha', 0.3)
    show = kwargs.get('show', True)

    # Validate columns
    if x_col not in data_df.columns:
        raise ValueError(f"Column '{x_col}' not found in data_df.")
    if y_col not in data_df.columns:
        raise ValueError(f"Column '{y_col}' not found in data_df.")

    # Get first dataset (drop NaN values)
    valid_mask = data_df[x_col].notna() & data_df[y_col].notna()
    x_data = data_df.loc[valid_mask, x_col].values
    y_data = data_df.loc[valid_mask, y_col].values

    # Set labels from parameter
    if labels is not None:
        xlabel = labels[0]
        ylabel = labels[1] if len(labels) > 1 else y_col

    # Create figure
    fig, ax = plt.subplots(figsize=figure_size, dpi=dpi)
    
    # Stats dictionary to return
    stats_dict = {}

    # ========== Plot first dataset ==========
    ax.scatter(x_data, y_data, c=scatter_color, alpha=scatter_alpha,
               s=scatter_size, marker=scatter_marker,
               edgecolors=scatter_edgecolor, linewidth=scatter_linewidth,
               label=scatter_label)
    
    # Regression for first dataset
    if add_regression:
        slope, intercept, r_value, p_value, std_err = stats.linregress(x_data, y_data)
        r2 = r_value ** 2
        
        # Store stats
        stats_dict['dataset1'] = {
            'slope': slope,
            'intercept': intercept,
            'r': r_value,
            'r2': r2,
            'p_value': p_value,
            'std_err': std_err
        }
        
        # Plot regression line
        x_fit = np.linspace(x_data.min(), x_data.max(), 100)
        y_fit = slope * x_fit + intercept
        ax.plot(x_fit, y_fit, color=reg_color, linewidth=reg_linewidth,
                linestyle=reg_linestyle, alpha=reg_alpha)
        
        # Build annotation text
        annotation_parts = []
        if show_equation:
            sign = '+' if intercept >= 0 else '-'
            annotation_parts.append(f'y = {slope:.3f}x {sign} {abs(intercept):.3f}')
        if show_r2:
            annotation_parts.append(f'R² = {r2:.3f}')
        if show_corr:
            annotation_parts.append(f'coef. = {r_value:.2f}')
        
        if annotation_parts:
            annotation_text = '\n'.join(annotation_parts)
            
            # Determine annotation location
            if annotation_loc == 'auto':
                # Place based on slope direction
                if slope >= 0:
                    loc_xy = (0.05, 0.95)
                    va = 'top'
                else:
                    loc_xy = (0.05, 0.05)
                    va = 'bottom'
            else:
                loc_map = {
                    'upper left': ((0.05, 0.95), 'top'),
                    'upper right': ((0.95, 0.95), 'top'),
                    'lower left': ((0.05, 0.05), 'bottom'),
                    'lower right': ((0.95, 0.05), 'bottom'),
                }
                loc_xy, va = loc_map.get(annotation_loc, ((0.05, 0.95), 'top'))
            
            ha = 'left' if loc_xy[0] < 0.5 else 'right'
            
            ax.annotate(annotation_text, xy=loc_xy, xycoords='axes fraction',
                       fontsize=annotation_fontsize, fontweight='bold',
                       ha=ha, va=va,
                       bbox=dict(boxstyle='round', facecolor='white', alpha=0.8,
                                edgecolor=reg_color, linewidth=1))

    # ========== Plot second dataset (optional) ==========
    if data_df2 is not None:
        # Use same column names if not specified
        if x_col2 is None:
            x_col2 = x_col
        if y_col2 is None:
            y_col2 = y_col
            
        if x_col2 not in data_df2.columns:
            raise ValueError(f"Column '{x_col2}' not found in data_df2.")
        if y_col2 not in data_df2.columns:
            raise ValueError(f"Column '{y_col2}' not found in data_df2.")
        
        valid_mask2 = data_df2[x_col2].notna() & data_df2[y_col2].notna()
        x_data2 = data_df2.loc[valid_mask2, x_col2].values
        y_data2 = data_df2.loc[valid_mask2, y_col2].values
        
        ax.scatter(x_data2, y_data2, c=scatter2_color, alpha=scatter2_alpha,
                   s=scatter2_size, marker=scatter2_marker,
                   edgecolors=scatter2_edgecolor, linewidth=scatter2_linewidth,
                   label=scatter2_label)
        
        # Regression for second dataset
        if add_regression2:
            slope2, intercept2, r_value2, p_value2, std_err2 = stats.linregress(x_data2, y_data2)
            r2_2 = r_value2 ** 2
            
            # Store stats
            stats_dict['dataset2'] = {
                'slope': slope2,
                'intercept': intercept2,
                'r': r_value2,
                'r2': r2_2,
                'p_value': p_value2,
                'std_err': std_err2
            }
            
            # Plot regression line
            x_fit2 = np.linspace(x_data2.min(), x_data2.max(), 100)
            y_fit2 = slope2 * x_fit2 + intercept2
            ax.plot(x_fit2, y_fit2, color=reg2_color, linewidth=reg2_linewidth,
                    linestyle=reg2_linestyle, alpha=reg2_alpha)
            
            # Build annotation text for second dataset
            annotation_parts2 = []
            if show_equation2:
                sign2 = '+' if intercept2 >= 0 else '-'
                annotation_parts2.append(f'y = {slope2:.3f}x {sign2} {abs(intercept2):.3f}')
            if show_r2_2:
                annotation_parts2.append(f'R² = {r2_2:.3f}')
            
            if annotation_parts2:
                annotation_text2 = '\n'.join(annotation_parts2)
                
                # Determine annotation location for second dataset
                if annotation2_loc == 'auto':
                    # Place on opposite side from first annotation
                    if add_regression and slope >= 0:
                        loc_xy2 = (0.95, 0.05)
                        va2 = 'bottom'
                    else:
                        loc_xy2 = (0.95, 0.95)
                        va2 = 'top'
                else:
                    loc_map = {
                        'upper left': ((0.05, 0.95), 'top'),
                        'upper right': ((0.95, 0.95), 'top'),
                        'lower left': ((0.05, 0.05), 'bottom'),
                        'lower right': ((0.95, 0.05), 'bottom'),
                    }
                    loc_xy2, va2 = loc_map.get(annotation2_loc, ((0.95, 0.05), 'bottom'))
                
                ha2 = 'left' if loc_xy2[0] < 0.5 else 'right'
                
                ax.annotate(annotation_text2, xy=loc_xy2, xycoords='axes fraction',
                           fontsize=annotation_fontsize, fontweight='bold',
                           ha=ha2, va=va2,
                           bbox=dict(boxstyle='round', facecolor='white', alpha=0.8,
                                    edgecolor=reg2_color, linewidth=1))

    # ========== Styling ==========
    if show_grid:
        ax.grid(True, alpha=grid_alpha)
    
    ax.spines['top'].set_visible(False)
    ax.spines['right'].set_visible(False)
    
    # Labels
    if not hide_labels:
        ax.set_xlabel(xlabel, fontsize=label_size, fontweight='bold')
        ax.set_ylabel(ylabel, fontsize=label_size, fontweight='bold')
    
    if title:
        ax.set_title(title, fontsize=title_size, fontweight='bold')
    
    # Legend
    if show_legend is None:
        # Auto-detect: show legend if any label is provided
        show_legend = scatter_label is not None or scatter2_label is not None
    
    if show_legend:
        ax.legend(loc=legend_loc, framealpha=0.9)

    plt.tight_layout()

    # Save figure if path provided
    if figure_folder is not None and filename is not None:
        save_path = Path(figure_folder) / filename
        plt.savefig(str(save_path),
                    bbox_inches='tight',
                    pad_inches=0,
                    transparent=True)

    if show:
        plt.show()

    return fig, ax, stats_dict

def stacked_proportion_plot(data_df,
                            bin_col,
                            value_col,
                            n_bins=4,
                            bin_method='quantile',
                            custom_bins=None,
                            figure_size=(10, 6),
                            dpi=350,
                            figure_folder=None,
                            filename=None,
                            **kwargs
                            ):
    """
    Create a 100% stacked bar chart showing the proportion of value categories 
    across bins of another variable.
    
    This is useful for analyzing how the distribution of a discrete variable (e.g., 
    diff_fleet_size = 1, 2, 3) changes across ranges of a continuous variable 
    (e.g., collaboration_rate = 0.4-0.6, 0.6-0.8, 0.8-1.0).
    
    Args:
        data_df: DataFrame containing the data
        bin_col: Column name to bin/group (e.g., 'collaboration_rate')
        value_col: Column name for value categories to show proportions (e.g., 'diff_fleet_size')
        n_bins: Number of bins to create (default: 4)
        bin_method: Method for binning - 'quantile', 'equal', or 'custom' (default: 'quantile')
            - 'quantile': Equal frequency bins (each bin has ~same number of samples)
            - 'equal': Equal width bins (evenly spaced)
            - 'custom': Use custom_bins parameter
        custom_bins: List of bin edges for 'custom' method (e.g., [0, 0.4, 0.6, 0.8, 1.0])
        figure_size: Figure size as tuple (width, height) (default: (10, 6))
        dpi: Figure resolution (default: 350)
        figure_folder: Folder path to save figure (optional)
        filename: Filename to save figure (optional)

    Keyword Args:
        # Color options
        colors: List of colors for each value category (default: auto from colormap)
        cmap: Colormap name for auto-generating colors (default: 'tab10')
        
        # Bar options
        bar_width: Width of bars (default: 0.7)
        bar_edgecolor: Edge color for bars (default: 'white')
        bar_linewidth: Edge line width for bars (default: 1)
        
        # Value display options
        show_percentages: Whether to show percentage labels on bars (default: True)
        percentage_threshold: Min percentage to show label (default: 5)
        percentage_fontsize: Font size for percentage labels (default: 10)
        percentage_color: Color for percentage labels (default: 'white')
        
        # Count display options
        show_counts: Whether to show sample count above each bar (default: True)
        count_fontsize: Font size for count labels (default: 10)
        count_format: Format string for count (default: 'n={n}')
        
        # Labels and display options
        xlabel: X-axis label (default: bin_col)
        ylabel: Y-axis label (default: 'Proportion (%)')
        title: Plot title (default: '')
        label_size: Font size for axis labels (default: 12)
        title_size: Font size for title (default: 14)
        
        # Legend options
        show_legend: Whether to show legend (default: True)
        legend_title: Title for legend (default: value_col)
        legend_loc: Location for legend (default: 'upper right')
        legend_bbox: Bbox_to_anchor for legend (default: None)
        
        # Bin label options
        bin_label_format: Format for bin labels - 'range', 'midpoint', or custom callable
            (default: 'range')
        bin_decimal: Decimal places for bin labels (default: 2)
        
        show: Whether to display the plot (default: True)

    Returns:
        fig: matplotlib Figure object
        ax: matplotlib Axes object
        result_df: DataFrame with proportion data

    Examples:
        # Basic usage - analyze fleet diff distribution across collaboration rate bins
        stacked_proportion_plot(df, 'collaboration_rate', 'diff_fleet_size', n_bins=4)
        
        # Equal width bins with custom colors
        stacked_proportion_plot(df, 'collab_rate', 'fleet_diff',
                               bin_method='equal', n_bins=5,
                               colors=['#e41a1c', '#377eb8', '#4daf4a'])
        
        # Custom bin edges
        stacked_proportion_plot(df, 'collaboration_rate', 'diff_fleet_size',
                               bin_method='custom',
                               custom_bins=[0, 0.4, 0.6, 0.8, 1.0])
        
        # Hide percentages, show only counts
        stacked_proportion_plot(df, 'rate', 'category',
                               show_percentages=False, show_counts=True)
    """
    import pandas as pd
    
    # Extract kwargs with defaults
    colors = kwargs.get('colors', None)
    cmap_name = kwargs.get('cmap', 'tab10')
    
    bar_width = kwargs.get('bar_width', 0.7)
    bar_edgecolor = kwargs.get('bar_edgecolor', 'white')
    bar_linewidth = kwargs.get('bar_linewidth', 1)
    
    show_percentages = kwargs.get('show_percentages', True)
    percentage_threshold = kwargs.get('percentage_threshold', 5)
    percentage_fontsize = kwargs.get('percentage_fontsize', 10)
    percentage_color = kwargs.get('percentage_color', 'white')
    
    show_counts = kwargs.get('show_counts', True)
    count_fontsize = kwargs.get('count_fontsize', 10)
    count_format = kwargs.get('count_format', 'n={n}')
    
    xlabel = kwargs.get('xlabel', bin_col)
    ylabel = kwargs.get('ylabel', 'Proportion (%)')
    title = kwargs.get('title', '')
    label_size = kwargs.get('label_size', 12)
    title_size = kwargs.get('title_size', 14)
    
    show_legend = kwargs.get('show_legend', True)
    legend_title = kwargs.get('legend_title', value_col)
    legend_loc = kwargs.get('legend_loc', 'upper right')
    legend_bbox = kwargs.get('legend_bbox', None)
    legend_ncol = kwargs.get('legend_ncol', 1)  # Number of columns in legend
    
    bin_label_format = kwargs.get('bin_label_format', 'range')
    bin_decimal = kwargs.get('bin_decimal', 2)
    
    show = kwargs.get('show', True)

    # Validate columns
    if bin_col not in data_df.columns:
        raise ValueError(f"Column '{bin_col}' not found in data_df.")
    if value_col not in data_df.columns:
        raise ValueError(f"Column '{value_col}' not found in data_df.")

    # Drop NaN values
    df = data_df[[bin_col, value_col]].dropna().copy()
    
    # Create bins
    # Note: include_lowest=True makes the first interval left-inclusive [a, b] instead of (a, b]
    # This ensures boundary values like exactly 0.4 are included when custom_bins=[0.4, 0.6, ...]
    if bin_method == 'quantile':
        df['bin'], bin_edges = pd.qcut(df[bin_col], q=n_bins, retbins=True, duplicates='drop')
    elif bin_method == 'equal':
        df['bin'], bin_edges = pd.cut(df[bin_col], bins=n_bins, retbins=True, include_lowest=True)
    elif bin_method == 'custom':
        if custom_bins is None:
            raise ValueError("custom_bins must be provided when bin_method='custom'")
        df['bin'], bin_edges = pd.cut(df[bin_col], bins=custom_bins, retbins=True, include_lowest=True)
    else:
        raise ValueError(f"Unknown bin_method: {bin_method}. Use 'quantile', 'equal', or 'custom'.")
    
    # Get unique values in value_col (sorted)
    unique_values = sorted(df[value_col].dropna().unique())
    n_values = len(unique_values)
    
    # Create bin labels
    bin_categories = df['bin'].cat.categories
    n_actual_bins = len(bin_categories)
    
    if callable(bin_label_format):
        bin_labels = [bin_label_format(interval) for interval in bin_categories]
    elif bin_label_format == 'range':
        bin_labels = [f'{interval.left:.{bin_decimal}f}-{interval.right:.{bin_decimal}f}' 
                     for interval in bin_categories]
    elif bin_label_format == 'midpoint':
        bin_labels = [f'{interval.mid:.{bin_decimal}f}' for interval in bin_categories]
    else:
        bin_labels = [str(interval) for interval in bin_categories]
    
    # Calculate proportions
    crosstab = pd.crosstab(df['bin'], df[value_col], normalize='index') * 100
    counts = df.groupby('bin').size()
    
    # Ensure all values are present in crosstab
    for val in unique_values:
        if val not in crosstab.columns:
            crosstab[val] = 0
    crosstab = crosstab[unique_values]  # Reorder columns
    
    # Generate colors if not provided
    if colors is None:
        cmap = plt.cm.get_cmap(cmap_name)
        colors = [cmap(i) for i in range(n_values)]
    elif len(colors) < n_values:
        # Extend colors if not enough
        colors = list(colors) * ((n_values // len(colors)) + 1)
        colors = colors[:n_values]
    
    # Create figure
    fig, ax = plt.subplots(figsize=figure_size, dpi=dpi)
    
    # Plot stacked bars
    x_positions = np.arange(n_actual_bins)
    bottom = np.zeros(n_actual_bins)
    
    bars_dict = {}
    for i, val in enumerate(unique_values):
        heights = crosstab[val].values
        bars = ax.bar(x_positions, heights, bar_width, bottom=bottom,
                     label=str(val), color=colors[i],
                     edgecolor=bar_edgecolor, linewidth=bar_linewidth)
        bars_dict[val] = bars
        
        # Add percentage labels
        if show_percentages:
            for j, (bar, height) in enumerate(zip(bars, heights)):
                if height >= percentage_threshold:
                    ax.text(bar.get_x() + bar.get_width() / 2,
                           bottom[j] + height / 2,
                           f'{height:.1f}%',
                           ha='center', va='center',
                           fontsize=percentage_fontsize,
                           color=percentage_color,
                           fontweight='bold')
        
        bottom += heights
    
    # Add sample counts above bars
    if show_counts:
        for j, (x_pos, count) in enumerate(zip(x_positions, counts.values)):
            ax.text(x_pos, 102, count_format.format(n=count),
                   ha='center', va='bottom',
                   fontsize=count_fontsize, fontweight='bold')
    
    # Styling
    ax.set_xticks(x_positions)
    ax.set_xticklabels(bin_labels, rotation=45, ha='right')
    ax.set_ylim(0, 110 if show_counts else 105)
    ax.set_xlim(-0.5, n_actual_bins - 0.5)
    
    ax.set_xlabel(xlabel, fontsize=label_size, fontweight='bold')
    ax.set_ylabel(ylabel, fontsize=label_size, fontweight='bold')
    
    if title:
        ax.set_title(title, fontsize=title_size, fontweight='bold')
    
    ax.spines['top'].set_visible(False)
    ax.spines['right'].set_visible(False)
    
    # Add horizontal line at 100%
    ax.axhline(y=100, color='gray', linestyle='--', linewidth=0.8, alpha=0.5)
    
    # Legend
    if show_legend:
        legend_kwargs = {
            'title': legend_title,
            'loc': legend_loc,
            'framealpha': 0.9,
            'ncol': legend_ncol
        }
        if legend_bbox:
            legend_kwargs['bbox_to_anchor'] = legend_bbox
        ax.legend(**legend_kwargs)

    plt.tight_layout()

    # Save figure if path provided
    if figure_folder is not None and filename is not None:
        save_path = Path(figure_folder) / filename
        plt.savefig(str(save_path),
                    bbox_inches='tight',
                    pad_inches=0,
                    transparent=True)

    if show:
        plt.show()

    # Create result DataFrame
    result_df = crosstab.copy()
    result_df['count'] = counts
    result_df.index = bin_labels
    result_df.index.name = bin_col + '_bin'

    return fig, ax, result_df


def network_locations_plot(network=None,
                           network_links_gdf=None,
                           network_nodes_gdf=None,
                           locations_gdf=None,
                           type_col='type',
                           figure_size=(12, 12),
                           dpi=350,
                           figure_folder=None,
                           filename=None,
                           xlim=None,
                           ylim=None,
                           **kwargs
                           ):
    """
    Visualize carrier and receiver locations on a transportation network.
    
    This function plots network links and nodes with carrier/receiver locations overlaid.
    Supports both matsim network objects and GeoDataFrames directly.
    
    Args:
        network: MATSim network object from matsim.read_network() (optional).
            If provided, will extract links and nodes from it.
        network_links_gdf: GeoDataFrame of network links (optional if network is provided).
        network_nodes_gdf: GeoDataFrame of network nodes (optional if network is provided).
        locations_gdf: GeoDataFrame containing carrier and receiver locations.
            Must have a geometry column and a type column to distinguish carriers/receivers.
        type_col: Column name in locations_gdf that identifies point type (default: 'type').
            Expected values: 'carrier', 'receiver', 'depot', etc.
        figure_size: Figure size as tuple (width, height) (default: (12, 12))
        dpi: Figure resolution (default: 350)
        figure_folder: Folder path to save figure (optional)
        filename: Filename to save figure (optional)
        xlim: Tuple (xmin, xmax) to limit x-axis range (default: None, auto)
        ylim: Tuple (ymin, ymax) to limit y-axis range (default: None, auto)

    Keyword Args:
        # Network display options
        show_links: Whether to show network links (default: True)
        show_nodes: Whether to show network nodes (default: True)
        use_arrows: Whether to draw links as arrows (default: True)
        link_color: Color for network links (default: 'gray')
        link_linewidth: Line width for links (default: 0.8)
        link_alpha: Transparency for links (default: 0.7)
        arrow_scale: Arrow head scale (default: 8)
        arrow_shrink: Arrow shrink from endpoints (default: 3)
        node_color: Color for network nodes (default: 'lightgray')
        node_size: Size for network nodes (default: 10)
        node_alpha: Transparency for nodes (default: 1.0)
        
        # Central region display options
        show_central_region: Whether to show central region rectangle (default: False)
        central_region_bounds: Tuple (xmin, ymin, width, height) for central region (default: (2000, 2000, 5000, 5000))
        central_region_color: Color for central region (default: '#4a90d9')
        central_region_alpha: Transparency for central region (default: 0.08)
        central_region_edge_alpha: Edge transparency (default: 1.0)
        central_region_linestyle: Line style (default: '--')
        central_region_linewidth: Line width (default: 2)
        central_region_label: Label for legend (default: 'Central Region')
        show_central_shadow: Whether to show shadow effect (default: True)
        shadow_offset: Shadow offset (x, y) (default: (50, -50))
        shadow_color: Shadow color (default: 'lightblue')
        shadow_alpha: Shadow transparency (default: 0.15)
        
        # Carrier display options
        carrier_color: Color for carrier/depot points (default: 'red')
        carrier_size: Marker size for carriers (default: 200)
        carrier_marker: Marker style for carriers (default: '*')
        carrier_alpha: Transparency for carriers (default: 1.0)
        carrier_edgecolor: Edge color for carrier markers (default: 'darkred')
        carrier_linewidth: Edge line width for carriers (default: 1)
        carrier_label: Label for carrier legend (default: 'Depot')
        carrier_types: List of type values to treat as carriers (default: ['carrier', 'depot'])
        
        # Receiver display options
        receiver_color: Color for receiver points (default: 'orange')
        receiver_size: Marker size for receivers (default: 100)
        receiver_marker: Marker style for receivers (default: 'o')
        receiver_alpha: Transparency for receivers (default: 0.7)
        receiver_edgecolor: Edge color for receiver markers (default: 'darkorange')
        receiver_linewidth: Edge line width for receivers (default: 1)
        receiver_label: Label for receiver legend (default: 'Receiver')
        receiver_types: List of type values to treat as receivers (default: ['receiver'])
        
        # Color grouping for receivers (optional)
        receiver_color_col: Column name to color receivers by value (default: None)
        receiver_cmap: Colormap for receiver coloring (default: 'viridis')
        receiver_cmap_vmin: Min value for colormap (default: auto)
        receiver_cmap_vmax: Max value for colormap (default: auto)
        show_colorbar: Whether to show colorbar when receiver_color_col is set (default: True)
        colorbar_label: Label for colorbar (default: receiver_color_col)
        
        # Size grouping for receivers (optional)
        receiver_size_col: Column name to size receivers by value (default: None)
        receiver_size_min: Min marker size (default: 30)
        receiver_size_max: Max marker size (default: 200)
        
        # Location positioning options
        use_link_midpoint: Whether to position locations at link midpoints (default: True).
            If True and link_id_col exists in locations_gdf, locations will be plotted
            at the midpoint of their associated link instead of their geometry coordinates.
        link_id_col: Column name containing link IDs for midpoint positioning (default: 'link_id')
        
        # Labels and display options
        title: Plot title (default: '')
        title_size: Font size for title (default: 14)
        xlabel: X-axis label (default: 'X Coordinate (m)')
        ylabel: Y-axis label (default: 'Y Coordinate (m)')
        label_size: Font size for axis labels (default: 12)
        hide_labels: Whether to hide axis labels (default: False)
        hide_axis: Whether to hide axis entirely (default: False)
        
        # Legend options
        show_legend: Whether to show legend (default: True)
        legend_loc: Location for legend (default: 'upper right')
        legend_fontsize: Font size for legend (default: 10)
        legend_framealpha: Legend background alpha (default: 0.9)
        
        # Grid and style options
        show_grid: Whether to show grid (default: True)
        grid_alpha: Grid transparency (default: 0.3)
        equal_aspect: Whether to use equal aspect ratio (default: True)
        
        show: Whether to display the plot (default: True)
        ax: Existing matplotlib axis to plot on (default: None, creates new figure)

    Returns:
        fig: matplotlib Figure object
        ax: matplotlib Axes object

    Examples:
        # Using matsim network object
        network = matsim.read_network('output_network.xml.gz')
        network_locations_plot(network=network, locations_gdf=receivers_gdf)
        
        # With central region highlight
        network_locations_plot(network=network, locations_gdf=receivers_gdf,
                              show_central_region=True,
                              central_region_bounds=(2000, 2000, 5000, 5000))
        
        # With axis limits
        network_locations_plot(network=network, locations_gdf=receivers_gdf,
                              xlim=(1000, 8000), ylim=(1000, 8000))
    """
    import geopandas as gpd
    from matplotlib.patches import Rectangle, FancyBboxPatch, FancyArrowPatch
    
    # Extract kwargs with defaults
    # Network options
    show_links = kwargs.get('show_links', True)
    show_nodes = kwargs.get('show_nodes', True)
    use_arrows = kwargs.get('use_arrows', True)
    link_color = kwargs.get('link_color', 'gray')
    link_linewidth = kwargs.get('link_linewidth', 0.8)
    link_alpha = kwargs.get('link_alpha', 0.7)
    arrow_scale = kwargs.get('arrow_scale', 8)
    arrow_shrink = kwargs.get('arrow_shrink', 3)
    node_color = kwargs.get('node_color', 'lightgray')
    node_size = kwargs.get('node_size', 10)
    node_alpha = kwargs.get('node_alpha', 1.0)
    
    # Central region options
    show_central_region = kwargs.get('show_central_region', False)
    central_region_bounds = kwargs.get('central_region_bounds', (2000, 2000, 5000, 5000))
    central_region_color = kwargs.get('central_region_color', '#4a90d9')
    central_region_alpha = kwargs.get('central_region_alpha', 0.08)
    central_region_edge_alpha = kwargs.get('central_region_edge_alpha', 1.0)
    central_region_linestyle = kwargs.get('central_region_linestyle', '--')
    central_region_linewidth = kwargs.get('central_region_linewidth', 2)
    central_region_label = kwargs.get('central_region_label', 'Central Region')
    show_central_shadow = kwargs.get('show_central_shadow', True)
    shadow_offset = kwargs.get('shadow_offset', (50, -50))
    shadow_color = kwargs.get('shadow_color', 'lightblue')
    shadow_alpha = kwargs.get('shadow_alpha', 0.15)
    
    # Carrier options
    carrier_color = kwargs.get('carrier_color', 'red')
    carrier_size = kwargs.get('carrier_size', 200)
    carrier_marker = kwargs.get('carrier_marker', '*')
    carrier_alpha = kwargs.get('carrier_alpha', 1.0)
    carrier_edgecolor = kwargs.get('carrier_edgecolor', 'darkred')
    carrier_linewidth = kwargs.get('carrier_linewidth', 1)
    carrier_label = kwargs.get('carrier_label', 'Depot')
    carrier_types = kwargs.get('carrier_types', ['carrier', 'depot'])
    
    # Receiver options
    receiver_color = kwargs.get('receiver_color', 'orange')
    receiver_size = kwargs.get('receiver_size', 100)
    receiver_marker = kwargs.get('receiver_marker', 'o')
    receiver_alpha = kwargs.get('receiver_alpha', 0.7)
    receiver_edgecolor = kwargs.get('receiver_edgecolor', 'darkorange')
    receiver_linewidth = kwargs.get('receiver_linewidth', 1)
    receiver_label = kwargs.get('receiver_label', 'Receiver')
    receiver_types = kwargs.get('receiver_types', ['receiver'])
    
    # Receiver color grouping
    receiver_color_col = kwargs.get('receiver_color_col', None)
    receiver_cmap = kwargs.get('receiver_cmap', 'viridis')
    receiver_cmap_vmin = kwargs.get('receiver_cmap_vmin', None)
    receiver_cmap_vmax = kwargs.get('receiver_cmap_vmax', None)
    show_colorbar = kwargs.get('show_colorbar', True)
    colorbar_label = kwargs.get('colorbar_label', receiver_color_col)
    
    # Receiver size grouping
    receiver_size_col = kwargs.get('receiver_size_col', None)
    receiver_size_min = kwargs.get('receiver_size_min', 30)
    receiver_size_max = kwargs.get('receiver_size_max', 200)
    
    # Labels and display
    title = kwargs.get('title', '')
    title_size = kwargs.get('title_size', 14)
    xlabel = kwargs.get('xlabel', 'X Coordinate (m)')
    ylabel = kwargs.get('ylabel', 'Y Coordinate (m)')
    label_size = kwargs.get('label_size', 12)
    hide_labels = kwargs.get('hide_labels', False)
    hide_axis = kwargs.get('hide_axis', False)
    
    # Legend options
    show_legend = kwargs.get('show_legend', True)
    legend_loc = kwargs.get('legend_loc', 'upper right')
    legend_fontsize = kwargs.get('legend_fontsize', 10)
    legend_framealpha = kwargs.get('legend_framealpha', 0.9)
    
    # Grid options
    show_grid = kwargs.get('show_grid', True)
    grid_alpha = kwargs.get('grid_alpha', 0.3)
    equal_aspect = kwargs.get('equal_aspect', True)
    
    show = kwargs.get('show', True)
    ax_input = kwargs.get('ax', None)

    # Process network input
    links_gdf = network_links_gdf
    nodes_gdf = network_nodes_gdf
    links_df = None
    nodes_df = None
    
    if network is not None:
        # Extract from matsim network object
        if hasattr(network, 'links'):
            links_df = network.links
        if hasattr(network, 'nodes'):
            nodes_df = network.nodes
    
    # Build node_coords dictionary for arrow plotting
    node_coords = {}
    if nodes_df is not None and 'x' in nodes_df.columns and 'y' in nodes_df.columns:
        if 'node_id' in nodes_df.columns:
            for _, row in nodes_df.iterrows():
                node_coords[row['node_id']] = (row['x'], row['y'])
        else:
            for idx, row in nodes_df.iterrows():
                node_coords[idx] = (row['x'], row['y'])
    
    # Convert nodes to GeoDataFrame if needed
    if nodes_gdf is None and nodes_df is not None:
        from shapely.geometry import Point
        if isinstance(nodes_df, gpd.GeoDataFrame):
            nodes_gdf = nodes_df
        elif 'geometry' in nodes_df.columns:
            nodes_gdf = gpd.GeoDataFrame(nodes_df, geometry='geometry')
        elif 'x' in nodes_df.columns and 'y' in nodes_df.columns:
            nodes_gdf = gpd.GeoDataFrame(
                nodes_df,
                geometry=[Point(x, y) for x, y in zip(nodes_df['x'], nodes_df['y'])]
            )
    
    # Convert links to GeoDataFrame if needed (for non-arrow mode)
    if links_gdf is None and links_df is not None:
        from shapely.geometry import LineString
        if isinstance(links_df, gpd.GeoDataFrame):
            links_gdf = links_df
        elif 'geometry' in links_df.columns:
            links_gdf = gpd.GeoDataFrame(links_df, geometry='geometry')
        elif node_coords and 'from_node' in links_df.columns and 'to_node' in links_df.columns:
            geometries = []
            valid_indices = []
            for idx, row in links_df.iterrows():
                from_node = row['from_node']
                to_node = row['to_node']
                if from_node in node_coords and to_node in node_coords:
                    from_coord = node_coords[from_node]
                    to_coord = node_coords[to_node]
                    geometries.append(LineString([from_coord, to_coord]))
                    valid_indices.append(idx)
            
            if geometries:
                links_gdf = gpd.GeoDataFrame(
                    links_df.loc[valid_indices],
                    geometry=geometries
                )
        elif 'from_x' in links_df.columns and 'from_y' in links_df.columns and \
             'to_x' in links_df.columns and 'to_y' in links_df.columns:
            from shapely.geometry import LineString
            geometries = [
                LineString([(row['from_x'], row['from_y']), (row['to_x'], row['to_y'])])
                for _, row in links_df.iterrows()
            ]
            links_gdf = gpd.GeoDataFrame(links_df, geometry=geometries)

    # Create figure and axis
    if ax_input is None:
        fig, ax = plt.subplots(figsize=figure_size, dpi=dpi)
    else:
        ax = ax_input
        fig = ax.figure

    # ========== Draw central region rectangle ==========
    if show_central_region:
        cr_x, cr_y, cr_w, cr_h = central_region_bounds
        
        # Shadow rectangle (slightly offset)
        if show_central_shadow:
            shadow = FancyBboxPatch(
                (cr_x + shadow_offset[0], cr_y + shadow_offset[1]), cr_w, cr_h,
                boxstyle="round,pad=0.02",
                facecolor=shadow_color, alpha=shadow_alpha,
                edgecolor='None',
                zorder=0
            )
            ax.add_patch(shadow)
        
        # Main rectangle for central region
        central_region = Rectangle(
            (cr_x, cr_y), cr_w, cr_h,
            linewidth=central_region_linewidth,
            edgecolor=central_region_color,
            facecolor=central_region_color,
            alpha=central_region_alpha,
            linestyle=central_region_linestyle,
            zorder=0.5,
            label=central_region_label
        )
        ax.add_patch(central_region)

    # ========== Plot network links ==========
    if show_links and links_df is not None and use_arrows:
        # Draw links as directed arrows
        for _, link in links_df.iterrows():
            from_node = link['from_node']
            to_node = link['to_node']
            if from_node in node_coords and to_node in node_coords:
                x1, y1 = node_coords[from_node]
                x2, y2 = node_coords[to_node]
                arrow = FancyArrowPatch(
                    (x1, y1), (x2, y2),
                    arrowstyle='->',
                    mutation_scale=arrow_scale,
                    color=link_color,
                    linewidth=link_linewidth,
                    alpha=link_alpha,
                    shrinkA=arrow_shrink,
                    shrinkB=arrow_shrink,
                    zorder=1
                )
                ax.add_patch(arrow)
    elif show_links and links_gdf is not None:
        # Fallback to simple lines
        links_gdf.plot(ax=ax, color=link_color, linewidth=link_linewidth,
                       alpha=link_alpha, zorder=1)

    # ========== Plot network nodes ==========
    if show_nodes and nodes_df is not None:
        ax.scatter(nodes_df['x'], nodes_df['y'], c=node_color, s=node_size,
                   zorder=1.5, alpha=node_alpha, label='Network Nodes')
    elif show_nodes and nodes_gdf is not None:
        nodes_gdf.plot(ax=ax, color=node_color, markersize=node_size,
                       alpha=node_alpha, zorder=1.5)

    # ========== Plot locations ==========
    if locations_gdf is not None and len(locations_gdf) > 0:
        # Separate carriers and receivers
        carriers_mask = locations_gdf[type_col].isin(carrier_types)
        receivers_mask = locations_gdf[type_col].isin(receiver_types)
        
        carriers_gdf = locations_gdf[carriers_mask].copy()
        receivers_gdf = locations_gdf[receivers_mask].copy()
        
        # Build link midpoint lookup if we have link_id column and network data
        # This allows plotting locations at link midpoints instead of node positions
        link_midpoints = {}
        link_id_col = kwargs.get('link_id_col', 'link_id')
        use_link_midpoint = kwargs.get('use_link_midpoint', True)
        
        if use_link_midpoint and links_df is not None and node_coords:
            # Build link_id -> midpoint mapping
            for _, link in links_df.iterrows():
                from_node = link['from_node']
                to_node = link['to_node']
                if from_node in node_coords and to_node in node_coords:
                    x1, y1 = node_coords[from_node]
                    x2, y2 = node_coords[to_node]
                    mid_x = (x1 + x2) / 2
                    mid_y = (y1 + y2) / 2
                    # Use link_id if available, otherwise use index
                    if 'link_id' in link.index:
                        link_midpoints[link['link_id']] = (mid_x, mid_y)
                    else:
                        link_midpoints[link.name] = (mid_x, mid_y)
        
        # Helper function to get coordinates (from link midpoint or geometry)
        def get_plot_coords(gdf, link_id_col, link_midpoints):
            """Get x, y coordinates for plotting - use link midpoints if available"""
            if use_link_midpoint and link_id_col in gdf.columns and link_midpoints:
                x_coords = []
                y_coords = []
                for _, row in gdf.iterrows():
                    lid = row[link_id_col]
                    if lid in link_midpoints:
                        x_coords.append(link_midpoints[lid][0])
                        y_coords.append(link_midpoints[lid][1])
                    else:
                        # Fallback to geometry
                        x_coords.append(row.geometry.x)
                        y_coords.append(row.geometry.y)
                return np.array(x_coords), np.array(y_coords)
            else:
                # Use geometry coordinates
                return gdf.geometry.x.values, gdf.geometry.y.values
        
        # Plot receivers first (below carriers)
        if len(receivers_gdf) > 0:
            rec_x, rec_y = get_plot_coords(receivers_gdf, link_id_col, link_midpoints)
            
            # Determine sizes
            if receiver_size_col is not None and receiver_size_col in receivers_gdf.columns:
                size_values = receivers_gdf[receiver_size_col].values
                size_vmin = size_values.min()
                size_vmax = size_values.max()
                if size_vmax > size_vmin:
                    normalized = (size_values - size_vmin) / (size_vmax - size_vmin)
                else:
                    normalized = np.ones(len(size_values)) * 0.5
                r_sizes = receiver_size_min + normalized * (receiver_size_max - receiver_size_min)
            else:
                r_sizes = receiver_size
            
            # Determine colors
            if receiver_color_col is not None and receiver_color_col in receivers_gdf.columns:
                c_values = receivers_gdf[receiver_color_col].values
                if receiver_cmap_vmin is None:
                    receiver_cmap_vmin = np.nanmin(c_values)
                if receiver_cmap_vmax is None:
                    receiver_cmap_vmax = np.nanmax(c_values)
                
                scatter = ax.scatter(rec_x, rec_y,
                                    c=c_values, cmap=receiver_cmap,
                                    vmin=receiver_cmap_vmin, vmax=receiver_cmap_vmax,
                                    s=r_sizes, marker=receiver_marker,
                                    alpha=receiver_alpha,
                                    edgecolors=receiver_edgecolor,
                                    linewidths=receiver_linewidth,
                                    zorder=3,
                                    label=f'{receiver_label} (n={len(receivers_gdf)})')
                
                if show_colorbar:
                    cbar = fig.colorbar(scatter, ax=ax, shrink=0.6, pad=0.02)
                    if colorbar_label:
                        cbar.set_label(colorbar_label, fontsize=10)
            else:
                ax.scatter(rec_x, rec_y,
                          c=receiver_color, s=r_sizes, marker=receiver_marker,
                          alpha=receiver_alpha,
                          edgecolors=receiver_edgecolor,
                          linewidths=receiver_linewidth,
                          zorder=3,
                          label=f'{receiver_label} (n={len(receivers_gdf)})')
        
        # Plot carriers on top
        if len(carriers_gdf) > 0:
            car_x, car_y = get_plot_coords(carriers_gdf, link_id_col, link_midpoints)
            ax.scatter(car_x, car_y,
                      c=carrier_color, s=carrier_size, marker=carrier_marker,
                      alpha=carrier_alpha,
                      edgecolors=carrier_edgecolor,
                      linewidths=carrier_linewidth,
                      zorder=4,
                      label=f'{carrier_label} (n={len(carriers_gdf)})')

    # ========== Axis limits ==========
    if xlim is not None:
        ax.set_xlim(xlim)
    if ylim is not None:
        ax.set_ylim(ylim)

    # ========== Styling ==========
    if title:
        ax.set_title(title, fontsize=title_size)
    
    if not hide_labels:
        ax.set_xlabel(xlabel, fontsize=label_size, fontweight='bold')
        ax.set_ylabel(ylabel, fontsize=label_size, fontweight='bold')
        
    
    if hide_axis:
        ax.axis('off')
    
    if show_grid:
        ax.grid(True, alpha=grid_alpha)
    
    if equal_aspect:
        ax.set_aspect('equal')
    
    # Legend
    if show_legend:
        ax.legend(loc=legend_loc, fontsize=legend_fontsize,
                 framealpha=legend_framealpha)

    plt.tight_layout()

    # Save figure if path provided
    if figure_folder is not None and filename is not None:
        save_path = Path(figure_folder) / filename
        plt.savefig(str(save_path),
                    bbox_inches='tight',
                    pad_inches=0,
                    dpi=dpi)

    if show:
        plt.show()

    return fig, ax